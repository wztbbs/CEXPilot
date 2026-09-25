package com.cexpilot.market.series;

import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesQueryPolicyTest {

    private static final long FIVE_MIN_MS = 5 * 60_000L;
    private static final ZoneOffset ZONE = ZoneOffset.UTC;
    private static final SeriesCapability CAPABILITY =
            new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4);
    private static final java.time.Instant NOW = java.time.Instant.ofEpochMilli(100L * 86_400_000L);

    private static TimeRange range(long startMs, long endMs) {
        return new TimeRange(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs), ZONE);
    }

    @Test
    void alignedRangePassesUnchanged() {
        TimeRange r = range(0, 12 * FIVE_MIN_MS);
        assertSame(r, SeriesQueryPolicy.check(
                CandleInterval.FIVE_MINUTES, r, CAPABILITY, "binance", NOW));
    }

    @Test
    void unalignedRangeWidensToIntervalBoundaries() {
        // [10:03, 11:03) 5m → [10:00, 11:05)，13 根
        long start = 10 * 3_600_000L + 3 * 60_000L;
        long end = 11 * 3_600_000L + 3 * 60_000L;
        TimeRange effective = SeriesQueryPolicy.check(CandleInterval.FIVE_MINUTES,
                range(start, end), CAPABILITY, "binance", NOW);
        assertEquals(10 * 3_600_000L, effective.startInclusive().toEpochMilli());
        assertEquals(11 * 3_600_000L + 5 * 60_000L, effective.endExclusive().toEpochMilli());
    }

    @Test
    void unsupportedIntervalRejected() {
        SeriesCapability fiveOnly = new SeriesCapability(Set.of(CandleInterval.FIVE_MINUTES), 1500, 4);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SeriesQueryPolicy.check(CandleInterval.ONE_HOUR,
                        range(0, 3_600_000L), fiveOnly, "okx", NOW));
        assertTrue(e.getMessage().contains("不支持粒度"));
        assertTrue(e.getMessage().contains("okx"));
    }

    @Test
    void overBudgetRejected() {
        // 6000 根预算 = 6000 × 5m = 500h；取 600h 超出预算
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SeriesQueryPolicy.check(CandleInterval.FIVE_MINUTES,
                        range(0, 600L * 3_600_000L), CAPABILITY, "binance", NOW));
        assertTrue(e.getMessage().contains("区间过长"));
    }

    @Test
    void retentionLimitRejected() {
        SeriesCapability retained = new SeriesCapability(
                Set.of(CandleInterval.values()), 1500, 4, 30);
        // 起点在 40 天前，超出 30 天保留期
        TimeRange r = range(60L * 86_400_000L, 61L * 86_400_000L);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SeriesQueryPolicy.check(CandleInterval.ONE_HOUR, r,
                        retained, "binance", NOW));
        assertTrue(e.getMessage().contains("30"));
        // 起点在保留期内 → 放行
        TimeRange ok = range(95L * 86_400_000L, 96L * 86_400_000L);
        assertSame(ok, SeriesQueryPolicy.check(CandleInterval.ONE_HOUR, ok,
                retained, "binance", NOW));
    }
}
