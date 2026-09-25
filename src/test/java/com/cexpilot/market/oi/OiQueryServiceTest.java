package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.OiInterval;
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
 * OiQueryService 端到端（fake source + 固定时钟）：完整区间、缺失、未完结剔除、30 天保留期拒绝。
 */
class OiQueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long ONE_H_MS = 3_600_000L;
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    private static OiSource source(long skipTimestamp, Integer retentionDays) {
        return new OiSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(OiInterval.values()), 500, 8, retentionDays);
            }

            @Override
            public FetchResult fetch(String base, OiInterval interval, long startMs, long endMs) {
                List<OiPoint> points = new ArrayList<>();
                for (long t = startMs; t < endMs; t += interval.duration().toMillis()) {
                    if (t == skipTimestamp) {
                        continue;
                    }
                    points.add(new OiPoint(t, BigDecimal.ONE));
                }
                return new FetchResult(points, null);
            }
        };
    }

    private static OiQueryService service(OiSource source) {
        return new OiQueryService(List.of(source),
                new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static TimeSpec spec(String json) {
        try {
            return TimeSpecParser.parse(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String YESTERDAY =
            "{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                    + "\"segment\":\"full\",\"extent\":\"full_period\"}";

    @Test
    void yesterdayHas24PointsComplete() {
        OiQueryResult r = service(source(-1, null)).query(ZoneOffset.UTC, spec(YESTERDAY),
                NOW, Exchange.BINANCE, "BTC", OiInterval.ONE_HOUR, false);
        assertEquals(24, r.points().size());
        assertTrue(r.coverage().rangeComplete());
    }

    @Test
    void missingPointDetected() {
        long skip = Instant.parse("2026-09-23T05:00:00Z").toEpochMilli();
        OiQueryResult r = service(source(skip, null)).query(ZoneOffset.UTC, spec(YESTERDAY),
                NOW, Exchange.BINANCE, "BTC", OiInterval.ONE_HOUR, false);
        assertFalse(r.coverage().complete());
        assertEquals(List.of(skip), r.coverage().missing());
        assertEquals(23, r.points().size());
    }

    @Test
    void currentPeriodSampleKeptAsInstantValue() {
        // OI 采样是时刻值：12:03 查询时 12:00 的采样已经确定，不应再等一个周期；
        // 未来采样（13:00 起）仍剔除，coveredUntil 取最后采样时刻
        OiQueryResult r = service(source(-1, null)).query(ZoneOffset.UTC,
                spec("{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":0,"
                        + "\"segment\":\"full\",\"extent\":\"full_period\"}"),
                NOW, Exchange.BINANCE, "BTC", OiInterval.ONE_HOUR, false);
        assertTrue(r.coverage().complete());
        assertFalse(r.coverage().rangeComplete());
        assertTrue(r.coverage().droppedUnclosed());
        assertEquals(13, r.points().size());
        assertEquals(Instant.parse("2026-09-24T12:00:00Z").toEpochMilli(),
                r.points().get(12).timestamp());
        assertEquals(Instant.parse("2026-09-24T12:00:00Z").toEpochMilli(),
                (long) r.coverage().coveredUntilMs());
    }

    @Test
    void retentionLimitRejected() {
        // 40 天前的查询被 30 天保留期拒绝
        assertThrows(IllegalArgumentException.class, () -> service(source(-1, 30)).query(ZoneOffset.UTC,
                spec("{\"type\":\"rolling_window\",\"timezone\":\"UTC\",\"duration\":{\"value\":40,\"unit\":\"day\"}}"),
                NOW, Exchange.BINANCE, "BTC", OiInterval.ONE_HOUR, false));
    }
}
