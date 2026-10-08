package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.kline.KlineQueryService;
import com.cexpilot.market.kline.KlineQueryRequest;
import com.cexpilot.market.kline.KlineSource;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.market.markprice.MarkPriceSource;
import com.cexpilot.market.markprice.PriceType;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 省略粒度时由程序选择，显式粒度原样透出；假数据源记录真实请求粒度与价格类型，不调用外部服务。 */
class CandleAutoIntervalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-25T13:17:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> SHAPES = List.of("range_statistic", "time_series");
    private final List<CandleInterval> fetchedIntervals = new ArrayList<>();
    private final List<PriceType> requestedPriceTypes = new ArrayList<>();

    private static JsonNode timeSpec() throws Exception {
        return JSON.readTree("{\"type\":\"calendar_period\",\"unit\":\"month\",\"offset\":0,"
                + "\"segment\":\"full\",\"extent\":\"to_request_time\"}");
    }

    private List<Candle> candles(CandleInterval interval, long start, long end) {
        fetchedIntervals.add(interval);
        var rows = new ArrayList<Candle>();
        for (long t = start; t < end; t += interval.duration().toMillis()) {
            rows.add(new Candle(t, BigDecimal.TEN, BigDecimal.valueOf(12), BigDecimal.valueOf(9),
                    BigDecimal.valueOf(11), BigDecimal.ONE, BigDecimal.TEN, null));
        }
        return rows;
    }

    private KlineQueryService klineService() {
        List<KlineSource> sources = new ArrayList<>();
        for (Exchange exchange : Exchange.values()) {
            sources.add(new KlineSource() {
                public Exchange exchange() { return exchange; }
                public SeriesCapability capability() { return new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4); }
                public FetchResult fetch(KlineQueryRequest r) {
                    return new FetchResult(candles(r.interval(), r.range().startInclusive().toEpochMilli(),
                            r.range().endExclusive().toEpochMilli()), null);
                }
            });
        }
        return new KlineQueryService(sources, new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private MarkPriceQueryService priceService() {
        List<MarkPriceSource> sources = new ArrayList<>();
        for (Exchange exchange : Exchange.values()) {
            sources.add(new MarkPriceSource() {
                public Exchange exchange() { return exchange; }
                public SeriesCapability capability() { return new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4); }
                public FetchResult fetch(String base, PriceType type, CandleInterval interval, long start, long end) {
                    requestedPriceTypes.add(type);
                    return new FetchResult(candles(interval, start, end), null);
                }
            });
        }
        return new MarkPriceQueryService(sources, new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private TimeRangeQuery query(String provider, MetricSelector selector, String shape,
                                 Exchange exchange, String interval) throws Exception {
        ObjectNode instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        var binding = new MetricBinding("m1", "test.metric", exchange.displayName(), shape, instrument,
                "USDT", provider, selector, "test-v1");
        return new TimeRangeQuery(binding, TimeSpecParser.parse(timeSpec()), interval, false);
    }

    private static void assertMonthToDateQuality(MetricResult result, String shape, JsonNode json) {
        assertEquals("1h", ((MetricResult.SeriesMetadata) result.metadata()).interval());
        assertEquals("Asia/Shanghai", json.at("/requested_range/timezone").asText());
        assertEquals("2026-09-25 21:17:00", json.at("/requested_range/end_exclusive").asText());
        assertEquals("2026-09-25 21:00:00", json.at("/coverage/covered_until").asText());
        assertTrue(json.at("/coverage/range_complete").asBoolean());
        assertTrue(json.at("/coverage/dropped_unclosed").asBoolean());
        assertEquals(597, json.at("/coverage/actual_count").asInt());
        if ("range_statistic".equals(shape)) {
            assertEquals(597, json.path("candle_count").asInt());
            assertFalse(json.has("statistics_omitted"));
            assertEquals("2026-09-25 21:00:00", json.at("/actual_range/end_exclusive").asText());
        } else {
            assertEquals(597, json.path("samples").size());
        }
    }

    @Test
    void monthToDateSelectsHourlyForEveryDomainShapeAndExchange() throws Exception {
        var kline = new KlineMetricProvider(klineService());
        var prices = new MarkPriceMetricProvider(priceService(), mock(MarketDataService.class));
        var context = new RequestContext(ZONE, NOW);
        for (Exchange exchange : Exchange.values()) {
            for (String shape : SHAPES) {
                var klineQuery = query("kline", KlineMetric.CLOSE, shape, exchange, null);
                var klineResult = kline.query(klineQuery, context);
                assertMonthToDateQuality(klineResult, shape, MetricResultJson.write(klineQuery.binding(), klineResult));
                for (var selector : List.of(MarkPriceMetric.MARK_CLOSE, MarkPriceMetric.INDEX_CLOSE)) {
                    var priceQuery = query("mark_price", selector, shape, exchange, null);
                    var priceResult = prices.query(priceQuery, context);
                    assertMonthToDateQuality(priceResult, shape, MetricResultJson.write(priceQuery.binding(), priceResult));
                }
            }
        }
        // 每个请求的粒度都由程序选定，不依赖调用方传入
        assertEquals(12, fetchedIntervals.size());
        assertTrue(fetchedIntervals.stream().allMatch(i -> i == CandleInterval.ONE_HOUR));
        assertEquals(8, requestedPriceTypes.size());
        assertTrue(requestedPriceTypes.containsAll(List.of(PriceType.MARK, PriceType.INDEX)));
    }

    @Test
    void explicitFineIntervalStillFailsOverBudgetWithoutFetching() throws Exception {
        var provider = new KlineMetricProvider(klineService());
        var query = query("kline", KlineMetric.CLOSE, "range_statistic", Exchange.BINANCE, "5m");
        var error = assertThrows(IllegalArgumentException.class, () -> provider.query(query, new RequestContext(ZONE, NOW)));
        assertTrue(error.getMessage().contains("7168"), error.getMessage());
        assertTrue(error.getMessage().contains("超过单次查询预算"), error.getMessage());
        assertTrue(fetchedIntervals.isEmpty());
    }

    @Test
    void explicitSupportedIntervalAndIndexPriceArePreserved() throws Exception {
        var context = new RequestContext(ZONE, NOW);
        var klineQuery = query("kline", KlineMetric.CLOSE, "range_statistic", Exchange.BINANCE, "15m");
        var klineMetadata = (MetricResult.SeriesMetadata) new KlineMetricProvider(klineService())
                .query(klineQuery, context).metadata();
        assertEquals("15m", klineMetadata.interval());
        var priceQuery = query("mark_price", MarkPriceMetric.INDEX_CLOSE, "range_statistic", Exchange.BINANCE, "15m");
        var priceResult = new MarkPriceMetricProvider(priceService(), mock(MarketDataService.class)).query(priceQuery, context);
        var priceMetadata = (MetricResult.SeriesMetadata) priceResult.metadata();
        assertEquals("15m", priceMetadata.interval());
        assertTrue(MetricResultJson.write(priceQuery.binding(), priceResult).at("/interval_source").asText().equals("explicit"));
        assertTrue(requestedPriceTypes.stream().allMatch(t -> t == PriceType.INDEX));
        assertTrue(fetchedIntervals.stream().allMatch(i -> i == CandleInterval.FIFTEEN_MINUTES));
    }
}
