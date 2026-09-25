package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.market.markprice.MarkPriceSource;
import com.cexpilot.market.markprice.PriceType;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRangeResolver;
import com.fasterxml.jackson.databind.JsonNode;
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
 * get_mark_price_history / get_mark_price_statistics 端到端（fake source + 固定时钟）。
 */
class GetMarkPriceHistoryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    private static MarkPriceSource source() {
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
                List<Candle> candles = new ArrayList<>();
                int i = 0;
                for (long t = startMs; t < endMs; t += interval.duration().toMillis(), i++) {
                    candles.add(new Candle(t,
                            BigDecimal.valueOf(100 + i), BigDecimal.valueOf(102 + i),
                            BigDecimal.valueOf(99 + i), BigDecimal.valueOf(101 + i),
                            null, null, null));
                }
                return new FetchResult(candles, null);
            }
        };
    }

    private static MarkPriceQueryService service() {
        return new MarkPriceQueryService(List.of(source()),
                new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static com.cexpilot.runtime.ToolContext ctx() {
        return new com.cexpilot.runtime.ToolContext("t", null, null, NOW);
    }

    @Test
    void historyReturnsSeriesWithoutVolume() {
        try {
            JsonNode args = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"1h\","
                    + "\"price_type\":\"index\","
                    + "\"time\":{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                    + "\"segment\":\"full\",\"extent\":\"full_period\"}}");
            ToolResult result = new GetMarkPriceHistoryTool(null, service()).execute(args, ctx());
            assertTrue(result.ok(), () -> String.valueOf(result.error()));
            JsonNode facts = result.data();
            assertEquals("index", facts.path("price_type").asText());
            assertTrue(facts.path("coverage").path("range_complete").asBoolean());
            assertEquals(24, facts.path("candle_count").asInt());
            assertEquals(5, facts.path("candles_columns").size()); // 无 volume 列
            assertEquals("2026-09-23 00:00:00", facts.path("candles").get(0).get(0).asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void statisticsUsesPriceChangeFields() {
        try {
            JsonNode args = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"1h\","
                    + "\"time\":{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                    + "\"segment\":\"full\",\"extent\":\"full_period\"}}");
            ToolResult result = new GetMarkPriceStatisticsTool(null, service()).execute(args, ctx());
            assertTrue(result.ok(), () -> String.valueOf(result.error()));
            JsonNode stats = result.data().path("statistics");
            assertEquals("100", stats.path("start_price").asText());
            assertEquals("124", stats.path("end_price").asText());
            assertEquals("24.0000", stats.path("change_pct").asText());
            assertEquals("125", stats.path("high").asText());
            assertEquals("99", stats.path("low").asText());
            assertEquals("mark", result.data().path("price_type").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
