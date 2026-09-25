package com.cexpilot.market.trade;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TradePoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradeQueryService 流程：防御性去重/排序/越界剔除、中止原因透传、
 * 空区间失败、保留期检查。
 */
class TradeQueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long START = Instant.parse("2026-09-23T10:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-09-23T11:00:00Z").toEpochMilli();

    private static TimeSpec spec(String startDay, String endDay) throws Exception {
        return TimeSpecParser.parse(MAPPER.readTree(
                "{\"type\":\"absolute_range\",\"timezone\":\"UTC\","
                        + "\"start\":{\"year\":2026,\"month\":9,\"day\":" + startDay + ",\"time\":\"10:00:00\"},"
                        + "\"end\":{\"year\":2026,\"month\":9,\"day\":" + endDay + ",\"time\":\"11:00:00\"},"
                        + "\"end_mode\":\"exclusive\"}"));
    }

    private static TradePoint trade(String id, long timestamp) {
        return new TradePoint(id, timestamp, new BigDecimal("100"), BigDecimal.ONE, true);
    }

    private static TradeSource fakeSource(List<TradePoint> trades, String abortReason, Integer retentionDays) {
        return new TradeSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return retentionDays == null
                        ? new SeriesCapability(Set.of(), 1000, 20)
                        : new SeriesCapability(Set.of(), 1000, 20, retentionDays);
            }

            @Override
            public FetchResult fetch(String base, long startMs, long endMs) {
                return new FetchResult(trades, abortReason);
            }
        };
    }

    private static TradeQueryService service(TradeSource source) {
        return new TradeQueryService(List.of(source), new TimeRangeResolver(Clock.fixed(NOW, UTC)));
    }

    @Test
    void dedupesSortsAndDropsOutOfRangeTrades() throws Exception {
        List<TradePoint> messy = new ArrayList<>(List.of(
                trade("id2", START + 2),
                trade("id1", START + 1),
                trade("id1", START + 1),      // 重复 ID
                trade("id0", START - 1),      // 越左界
                trade("id9", END)));          // 越右界
        TradeQueryResult result = service(fakeSource(messy, null, null))
                .query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertTrue(result.complete());
        assertEquals(2, result.trades().size());
        assertEquals("id1", result.trades().get(0).tradeId());
        assertEquals("id2", result.trades().get(1).tradeId());
    }

    @Test
    void abortReasonPropagatesAndMarksIncomplete() throws Exception {
        TradeQueryResult result = service(fakeSource(List.of(trade("id1", START + 1)), "分页请求预算用尽", null))
                .query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertFalse(result.complete());
        assertEquals("分页请求预算用尽", result.abortReason());
        assertEquals(1, result.trades().size());
    }

    @Test
    void emptyRangeFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                service(fakeSource(List.of(), null, null))
                        .query(UTC, uncheckedSpec(), NOW, Exchange.BINANCE, "BTC"));
        assertTrue(e.getMessage().contains("没有成交数据"), e.getMessage());
    }

    @Test
    void futureRangeEndMarkedIncompleteAndFetchClamped() {
        // CR02：请求基准 12:03，查询「今天全天」（终点为次日 00:00）；
        // 分页自然结束不代表区间完整，拉取终点应被钳到请求时刻
        long[] captured = new long[2];
        TradeSource source = new TradeSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(), 1000, 20);
            }

            @Override
            public FetchResult fetch(String base, long startMs, long endMs) {
                captured[0] = startMs;
                captured[1] = endMs;
                return new FetchResult(List.of(trade("id1", NOW.toEpochMilli() - 3_600_000)), null);
            }
        };
        TimeSpec today = uncheckedToday();
        TradeQueryResult result = service(source).query(UTC, today, NOW, Exchange.BINANCE, "BTC");
        assertFalse(result.complete());
        assertTrue(result.abortReason().contains("请求基准时间"), result.abortReason());
        assertEquals(NOW.toEpochMilli(), captured[1]);
        // rolling_window 终点恰为请求时刻：不受影响，仍为完整
    }

    @Test
    void rollingWindowEndingAtRequestTimeIsComplete() throws Exception {
        TimeSpec rolling = TimeSpecParser.parse(MAPPER.readTree(
                "{\"type\":\"rolling_window\",\"timezone\":\"UTC\","
                        + "\"duration\":{\"value\":1,\"unit\":\"hour\"}}"));
        TradeQueryResult result = service(fakeSource(
                List.of(trade("id1", NOW.toEpochMilli() - 60_000)), null, null))
                .query(UTC, rolling, NOW, Exchange.BINANCE, "BTC");
        assertTrue(result.complete());
    }

    @Test
    void retentionExceededRejected() {
        // 保留 90 天，查询 100 天前的区间 → 查询前拒绝
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                service(fakeSource(List.of(), null, 90))
                        .query(UTC, uncheckedSpec100DaysAgo(), NOW, Exchange.BINANCE, "BTC"));
        assertTrue(e.getMessage().contains("90"), e.getMessage());
    }

    private static TimeSpec uncheckedSpec() {
        try {
            return spec("23", "23");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TimeSpec uncheckedToday() {
        try {
            return TimeSpecParser.parse(MAPPER.readTree(
                    "{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\","
                            + "\"offset\":0,\"segment\":\"full\",\"extent\":\"full_period\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TimeSpec uncheckedSpec100DaysAgo() {
        try {
            return TimeSpecParser.parse(MAPPER.readTree(
                    "{\"type\":\"absolute_range\",\"timezone\":\"UTC\","
                            + "\"start\":{\"year\":2026,\"month\":6,\"day\":15,\"time\":\"10:00:00\"},"
                            + "\"end\":{\"year\":2026,\"month\":6,\"day\":15,\"time\":\"11:00:00\"},"
                            + "\"end_mode\":\"exclusive\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
