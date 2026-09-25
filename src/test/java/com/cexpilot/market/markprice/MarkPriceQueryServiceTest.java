package com.cexpilot.market.markprice;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.CandleInterval;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarkPriceQueryService 端到端（fake source + 固定时钟）+ OKX 两段拆分分页。
 */
class MarkPriceQueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    private static MarkPriceSource source(PriceType seenType) {
        return new MarkPriceSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4);
            }

            @Override
            public FetchResult fetch(String base, PriceType priceType, CandleInterval interval,
                                     long startMs, long endMs) {
                assertEquals(seenType, priceType);
                List<Candle> candles = new ArrayList<>();
                for (long t = startMs; t < endMs; t += interval.duration().toMillis()) {
                    candles.add(new Candle(t, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                            BigDecimal.ONE, null, null, null));
                }
                return new FetchResult(candles, null);
            }
        };
    }

    private static MarkPriceQueryService service(MarkPriceSource source) {
        return new MarkPriceQueryService(List.of(source),
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
    void yesterdayMarkAndIndexBothComplete() {
        String yesterday = "{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                + "\"segment\":\"full\",\"extent\":\"full_period\"}";
        for (PriceType type : new PriceType[]{PriceType.MARK, PriceType.INDEX}) {
            MarkPriceQueryResult r = service(source(type)).query(ZoneOffset.UTC, spec(yesterday),
                    NOW, Exchange.BINANCE, "BTC", type, CandleInterval.ONE_HOUR, false);
            assertEquals(24, r.candles().size());
            assertTrue(r.coverage().rangeComplete());
            assertEquals(type, r.priceType());
        }
    }

    @Test
    void okxSplitsAcrossRecentAndHistoryEndpoints() {
        // 跨 1440 根分界：历史段与近期段各被调一次，缝合完整
        long now = 1_700_057_600_000L;
        long start = now - 2000L * 3_600_000L;
        long end = now;
        List<Boolean> calls = new ArrayList<>();
        OkxMarkPriceSource source = new OkxMarkPriceSource(
                (priceType, instId, bar, before, after, limit, history) -> {
                    calls.add(history);
                    long segStart = Math.max(start, history ? start : now - 1440L * 3_600_000L);
                    long segEnd = history ? now - 1440L * 3_600_000L : end;
                    List<Candle> candles = new ArrayList<>();
                    for (long t = segStart; t < Math.min(after, segEnd); t += 3_600_000L) {
                        candles.add(new Candle(t, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ONE, null, null, Boolean.TRUE));
                    }
                    return candles;
                }, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
        MarkPriceSource.FetchResult result = source.fetch("BTC", PriceType.MARK,
                CandleInterval.ONE_HOUR, start, end);
        assertEquals(List.of(true, false), calls);
        assertEquals(2000, result.candles().size());
        assertEquals(null, result.abortReason());
    }
}
