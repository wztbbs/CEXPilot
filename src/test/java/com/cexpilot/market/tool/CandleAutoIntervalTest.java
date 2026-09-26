package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.kline.*;
import com.cexpilot.market.markprice.*;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.runtime.*;
import com.cexpilot.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 YAML 参数缺省 → 两类 QueryService → 实际粒度及截止时间，不调用外部服务。 */
class CandleAutoIntervalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-25T13:17:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> TOOLS = List.of("get_klines", "get_market_statistics",
            "get_mark_price_history", "get_mark_price_statistics");
    private final List<CandleInterval> fetchedIntervals = new ArrayList<>();

    private List<Candle> candles(CandleInterval interval, long start, long end) {
        fetchedIntervals.add(interval);
        var rows = new ArrayList<Candle>();
        for (long t = start; t < end; t += interval.duration().toMillis()) {
            rows.add(new Candle(t, BigDecimal.TEN, BigDecimal.valueOf(12), BigDecimal.valueOf(9),
                    BigDecimal.valueOf(11), BigDecimal.ONE, BigDecimal.TEN, null));
        }
        return rows;
    }

    private ToolRegistry registry() {
        List<KlineSource> klines = new ArrayList<>();
        List<MarkPriceSource> prices = new ArrayList<>();
        for (Exchange exchange : Exchange.values()) {
            var capability = new SeriesCapability(Set.of(CandleInterval.values()),
                    exchange == Exchange.BINANCE ? 1500 : 300, exchange == Exchange.BINANCE ? 4 : 20);
            klines.add(new KlineSource() {
                public Exchange exchange() { return exchange; }
                public SeriesCapability capability() { return capability; }
                public FetchResult fetch(KlineQueryRequest r) {
                    return new FetchResult(candles(r.interval(), r.range().startInclusive().toEpochMilli(),
                            r.range().endExclusive().toEpochMilli()), null);
                }
            });
            prices.add(new MarkPriceSource() {
                public Exchange exchange() { return exchange; }
                public SeriesCapability capability() { return capability; }
                public FetchResult fetch(String base, PriceType type, CandleInterval interval, long start, long end) {
                    return new FetchResult(candles(interval, start, end), null);
                }
            });
        }
        var resolver = new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC));
        var klineService = new KlineQueryService(klines, resolver);
        var priceService = new MarkPriceQueryService(prices, resolver);
        List<AgentTool> tools = List.of(new GetKlinesTool(null, klineService), new GetMarketStatisticsTool(null, klineService),
                new GetMarkPriceHistoryTool(null, priceService), new GetMarkPriceStatisticsTool(null, priceService));
        return new ToolRegistry(tools, ToolDefinitionLoader.load(new DefaultResourceLoader()).stream()
                .filter(d -> TOOLS.contains(d.name())).toList());
    }

    private static ObjectNode args(Exchange exchange) throws Exception {
        var args = (ObjectNode) MAPPER.readTree("""
                {"symbol":"BTC","time":{"type":"calendar_period","unit":"month","offset":0,
                 "segment":"full","extent":"to_request_time"}}
                """);
        args.put("exchange", exchange.displayName());
        return args;
    }

    @Test
    void monthToDateSelectsHourlyForHistoryAndStatisticsOnBothExchanges() throws Exception {
        var registry = registry();
        for (Exchange exchange : Exchange.values()) {
            for (String name : TOOLS) {
                var prepared = registry.prepareArguments(name, args(exchange));
                assertFalse(prepared.has("interval"), "YAML 不得补回固定默认粒度");
                var result = registry.get(name).execute(prepared, new ToolContext("test", null, ZONE, NOW));
                assertTrue(result.ok(), result.error());
                var data = result.data();
                assertEquals("1h", data.path("candle_interval").asText());
                assertEquals("automatic", data.path("interval_source").asText());
                assertTrue(data.at("/coverage/range_complete").asBoolean());
                assertTrue(data.at("/coverage/dropped_unclosed").asBoolean());
                assertEquals(597, data.at("/coverage/actual_count").asInt());
                assertEquals("2026-09-25 21:17:00", data.at("/requested_range/end_exclusive").asText());
                assertEquals("2026-09-25 21:00:00", data.at("/coverage/covered_until").asText());
                if (name.endsWith("statistics")) {
                    assertEquals(597, data.at("/statistics/candle_count").asInt());
                    assertEquals("2026-09-25 21:00:00", data.at("/statistics/actual_range/end_exclusive").asText());
                    assertFalse(data.has("statistics_omitted"));
                } else assertEquals(597, data.path("candles").size());
            }
        }
        assertEquals(8, fetchedIntervals.size());
        assertTrue(fetchedIntervals.stream().allMatch(i -> i == CandleInterval.ONE_HOUR));
    }

    @Test
    void explicitFineIntervalStillFailsOverBudgetWithoutFetching() throws Exception {
        var registry = registry();
        for (String name : TOOLS) {
            var input = args(Exchange.BINANCE).put("interval", "5m");
            var result = registry.get(name).execute(registry.prepareArguments(name, input),
                    new ToolContext("test", null, ZONE, NOW));
            assertFalse(result.ok());
            assertTrue(result.error().contains("7168"), result.error());
        }
        assertTrue(fetchedIntervals.isEmpty());
    }

    @Test
    void explicitSupportedIntervalAndIndexPriceArePreserved() throws Exception {
        var registry = registry();
        for (String name : TOOLS) {
            var input = args(Exchange.BINANCE).put("interval", "15m");
            if (name.contains("mark_price")) input.put("price_type", "index");
            var result = registry.get(name).execute(registry.prepareArguments(name, input),
                    new ToolContext("test", null, ZONE, NOW));
            assertTrue(result.ok(), result.error());
            assertEquals("15m", result.data().path("candle_interval").asText());
            assertEquals("explicit", result.data().path("interval_source").asText());
            if (name.contains("mark_price")) assertEquals("index", result.data().path("price_type").asText());
        }
        assertTrue(fetchedIntervals.stream().allMatch(i -> i == CandleInterval.FIFTEEN_MINUTES));
    }
}
