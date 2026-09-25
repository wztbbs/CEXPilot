package com.cexpilot.market.funding;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FundingQueryService 端到端（fake source + 固定时钟）：
 * 预期结算序列、缺失核对、未结算剔除、部分覆盖。
 */
class FundingQueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long EIGHT_H_MS = 8 * 3_600_000L;
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    /** 返回 [startMs, endMs) 网格上的完整已结算序列（可跳过指定期）。 */
    private static FundingRateSource source(long skipFundingTime) {
        return new FundingRateSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(), 1000, 10);
            }

            @Override
            public long fundingIntervalMs(String base) {
                return EIGHT_H_MS;
            }

            @Override
            public FetchResult fetch(String base, long intervalMs, long startMs, long endMs) {
                List<FundingRatePoint> points = new ArrayList<>();
                for (long t = startMs % intervalMs == 0 ? startMs : (startMs / intervalMs + 1) * intervalMs;
                     t < endMs && t <= NOW.toEpochMilli(); t += intervalMs) {
                    if (t == skipFundingTime) {
                        continue;
                    }
                    points.add(new FundingRatePoint(new BigDecimal("0.0001"), t));
                }
                return new FetchResult(points, null);
            }
        };
    }

    private static FundingQueryService service(FundingRateSource source) {
        return new FundingQueryService(List.of(source),
                new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static TimeSpec spec(String json) {
        try {
            return TimeSpecParser.parse(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void yesterdayHasThreeSettlementsComplete() {
        // 昨天全天（UTC）8h 结算：00:00 / 08:00 / 16:00 共 3 期
        FundingQueryResult r = service(source(-1)).query(ZoneOffset.UTC,
                spec("{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                        + "\"segment\":\"full\",\"extent\":\"full_period\"}"),
                NOW, Exchange.BINANCE, "BTC");
        assertEquals(3, r.points().size());
        assertTrue(r.coverage().rangeComplete());
        assertEquals(Instant.parse("2026-09-23T00:00:00Z").toEpochMilli(), r.points().get(0).fundingTime());
        assertEquals(Instant.parse("2026-09-23T16:00:00Z").toEpochMilli(), r.points().get(2).fundingTime());
    }

    @Test
    void inconsistentSpacingFailsLoudly() {
        // 缺一期（或周期变更）时间隔不等于当前周期：响亮失败，不用单一网格误判缺失
        long skip = Instant.parse("2026-09-23T08:00:00Z").toEpochMilli();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(source(skip)).query(ZoneOffset.UTC,
                        spec("{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                                + "\"segment\":\"full\",\"extent\":\"full_period\"}"),
                        NOW, Exchange.BINANCE, "BTC"));
        assertTrue(e.getMessage().contains("结算间隔不一致"), e::getMessage);
    }

    @Test
    void todayIsPartialCoverageWithUnsettledDropped() {
        // 今天全天（UTC），NOW=12:03：已结算 00:00 / 08:00 两期，16:00/24:00 剔除
        FundingQueryResult r = service(source(-1)).query(ZoneOffset.UTC,
                spec("{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":0,"
                        + "\"segment\":\"full\",\"extent\":\"full_period\"}"),
                NOW, Exchange.BINANCE, "BTC");
        assertEquals(2, r.points().size());
        assertTrue(r.coverage().complete());
        assertFalse(r.coverage().rangeComplete());
        assertTrue(r.coverage().droppedUnclosed());
        assertEquals(Instant.parse("2026-09-24T08:00:00Z").toEpochMilli(),
                (long) r.coverage().coveredUntilMs());
    }

    @Test
    void unalignedRangeStillUsesSettlementGrid() {
        // 昨天 10:03 ~ 今天 10:03（rolling）：网格上的结算 16:00 / 24:00 / 08:00 共 3 期
        FundingQueryResult r = service(source(-1)).query(ZoneOffset.UTC,
                spec("{\"type\":\"rolling_window\",\"timezone\":\"UTC\",\"duration\":{\"value\":24,\"unit\":\"hour\"}}"),
                NOW, Exchange.BINANCE, "BTC");
        // NOW=12:03，过去 24h = [昨天12:03, 今天12:03]，已结算：昨天16:00、今天00:00、今天08:00
        assertEquals(3, r.points().size());
        assertTrue(r.coverage().complete());
    }

    @Test
    void futureSettlementMakesRangeIncomplete() {
        // R04 复现：今天 [00:00,18:00)，16:00 尚未结算——已结算部分齐全但区间不完整
        FundingQueryResult r = service(source(-1)).query(ZoneOffset.UTC,
                spec("{\"type\":\"relative_day_range\",\"timezone\":\"UTC\","
                        + "\"start\":{\"day_offset\":0,\"time\":\"00:00:00\"},"
                        + "\"end\":{\"day_offset\":0,\"time\":\"18:00:00\"}}"),
                NOW, Exchange.BINANCE, "BTC");
        assertEquals(2, r.points().size());
        assertTrue(r.coverage().complete());
        assertFalse(r.coverage().rangeComplete());
    }

    @Test
    void queryRecentExpandsWindowUntilCountReached() {
        // 早期为 8h 周期、最近调整为 4h：count=5 按 4h 估算只能取 2 期，扩窗后取满 5 期
        long fourH = 4 * 3_600_000L;
        long eightH = 8 * 3_600_000L;
        FundingRateSource changing = new FundingRateSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(), 1000, 10);
            }

            @Override
            public long fundingIntervalMs(String base) {
                return fourH; // 当前周期 4h
            }

            @Override
            public FetchResult fetch(String base, long intervalMs, long startMs, long endMs) {
                List<FundingRatePoint> points = new ArrayList<>();
                // 历史 8h 网格至 09-24T08:00 才切换 4h：首次 40h 窗口只取到 4 期，扩窗后取满
                for (long t = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();
                     t <= NOW.toEpochMilli(); t += eightH) {
                    if (t >= startMs && t < endMs && t < Instant.parse("2026-09-24T08:00:00Z").toEpochMilli()) {
                        points.add(new FundingRatePoint(new BigDecimal("0.0001"), t));
                    }
                }
                for (long t = Instant.parse("2026-09-24T08:00:00Z").toEpochMilli();
                     t <= NOW.toEpochMilli(); t += fourH) {
                    if (t >= startMs && t < endMs) {
                        points.add(new FundingRatePoint(new BigDecimal("0.0001"), t));
                    }
                }
                return new FetchResult(points, null);
            }
        };
        FundingRecentResult r = service(changing).queryRecent(Exchange.BINANCE, "BTC", 5, NOW);
        assertEquals(5, r.points().size());
    }

    @Test
    void rangeWithoutSettlementThrows() {
        // 区间不足一个结算周期且不含任何结算时刻
        assertThrows(IllegalArgumentException.class, () -> service(source(-1)).query(ZoneOffset.UTC,
                spec("{\"type\":\"relative_day_range\",\"timezone\":\"UTC\","
                        + "\"start\":{\"day_offset\":-1,\"time\":\"01:00:00\"},"
                        + "\"end\":{\"day_offset\":-1,\"time\":\"02:00:00\"}}"),
                NOW, Exchange.BINANCE, "BTC"));
    }
}
