package com.cexpilot.market.series;

import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static com.cexpilot.time.CandleInterval.*;
import static org.junit.jupiter.api.Assertions.*;

class CandleIntervalSelectorTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final SeriesCapability CAPABILITY = new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4);

    private static TimeRange range(Duration span) {
        return new TimeRange(START, START.plus(span), ZoneOffset.UTC);
    }

    @Test
    void shortMediumAndLongRangesUseOnePolicy() {
        assertEquals(FIVE_MINUTES, CandleIntervalSelector.select(null, range(Duration.ofDays(1)), CAPABILITY));
        assertEquals(FIFTEEN_MINUTES, CandleIntervalSelector.select(null, range(Duration.ofDays(10)), CAPABILITY));
        assertEquals(ONE_HOUR, CandleIntervalSelector.select(null, range(Duration.ofDays(26)), CAPABILITY));
    }

    @Test
    void alignedPointCountIncludesBothPartialEdges() {
        var aligned = range(Duration.ofMinutes(1500 * 5));
        assertEquals(FIVE_MINUTES, CandleIntervalSelector.select(null, aligned, CAPABILITY));
        var shifted = new TimeRange(aligned.startInclusive().plusSeconds(1), aligned.endExclusive().plusSeconds(1), ZoneOffset.UTC);
        assertEquals(FIFTEEN_MINUTES, CandleIntervalSelector.select(null, shifted, CAPABILITY));
    }

    @Test
    void targetIsNotHardLimitAndExistingBudgetStillApplies() {
        var longest = range(Duration.ofDays(250));
        var selected = CandleIntervalSelector.select(null, longest, CAPABILITY);
        assertEquals(ONE_HOUR, selected);
        assertEquals(longest, SeriesQueryPolicy.check(selected, longest, CAPABILITY, "binance", longest.endExclusive()));
        var tooLong = range(Duration.ofDays(251));
        assertThrows(IllegalArgumentException.class, () -> SeriesQueryPolicy.check(
                CandleIntervalSelector.select(null, tooLong, CAPABILITY), tooLong, CAPABILITY, "binance", tooLong.endExclusive()));
    }

    @Test
    void explicitIntervalIsNeverCoarsenedToPassBudget() {
        var month = range(Duration.ofDays(26));
        assertEquals(FIVE_MINUTES, CandleIntervalSelector.select(FIVE_MINUTES, month, CAPABILITY));
        assertThrows(IllegalArgumentException.class,
                () -> SeriesQueryPolicy.check(FIVE_MINUTES, month, CAPABILITY, "binance", month.endExclusive()));
    }

    @Test
    void onlySupportedIntervalsAndSourceBudgetAreUsed() {
        assertEquals(ONE_HOUR, CandleIntervalSelector.select(null, range(Duration.ofHours(1)),
                new SeriesCapability(Set.of(ONE_HOUR), 100, 1)));
        assertEquals(FIFTEEN_MINUTES, CandleIntervalSelector.select(null, range(Duration.ofHours(3)),
                new SeriesCapability(Set.of(CandleInterval.values()), 12, 1)));
    }
}
