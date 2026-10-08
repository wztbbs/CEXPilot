package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.markprice.MarkPriceQueryResult;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.market.markprice.PriceType;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 标记/指数价格 Provider + 假查询结果；统计口径对照 MarketCalculator.priceChange。 */
class MarkPriceMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-28T00:00:00Z");
    /** 快照数据时间；CONTEXT 时区为 UTC，指数价来源时间比标记价晚一分钟。 */
    private static final long SNAPSHOT_TIME = Instant.parse("2026-09-28T22:00:00Z").toEpochMilli();
    private static final RequestContext CONTEXT = new RequestContext(ZoneOffset.UTC, START.plusSeconds(86400));
    private static final TimeRange RANGE = new TimeRange(START, START.plusSeconds(7200), ZoneOffset.UTC);

    private static TimeRangeQuery query(MarkPriceMetric selector, String shape, String intervalCode) throws Exception {
        var instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        var binding = new MetricBinding("m1", "mark.test", "binance", shape, instrument,
                selector.name().endsWith("CHANGE_PCT") ? "percent" : "USDT",
                MarkPriceMetricProvider.NAME, selector, "test-v1");
        return new TimeRangeQuery(binding, TimeSpecParser.parse(JSON.readTree(
                "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"full\",\"extent\":\"full_period\"}")),
                intervalCode, false);
    }

    private static MarkPriceQueryResult data(PriceType priceType, boolean complete) {
        var candles = List.of(
                new Candle(START.toEpochMilli(), new BigDecimal("100"), new BigDecimal("105"),
                        new BigDecimal("98"), new BigDecimal("104"), BigDecimal.ZERO, null, true),
                new Candle(START.plusSeconds(3600).toEpochMilli(), new BigDecimal("104"), new BigDecimal("112"),
                        new BigDecimal("103"), new BigDecimal("110"), BigDecimal.ZERO, null, true));
        var coverage = new SeriesCoverage(2, 2, complete ? List.of() : List.of(START.toEpochMilli()),
                List.of(), List.of(), false, false, null, RANGE.endExclusive().toEpochMilli(), false);
        return new MarkPriceQueryResult(priceType, RANGE, RANGE, candles, coverage, CandleInterval.parse("1h"));
    }

    private static MarkPriceQueryService service(MarkPriceQueryResult result) {
        var service = mock(MarkPriceQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(result);
        return service;
    }

    @ParameterizedTest
    @CsvSource({"MARK_OPEN,100", "MARK_CLOSE,110", "MARK_HIGH,112", "MARK_LOW,98", "MARK_CHANGE_PCT,10.0000",
            "INDEX_OPEN,100", "INDEX_CLOSE,110", "INDEX_HIGH,112", "INDEX_LOW,98", "INDEX_CHANGE_PCT,10.0000"})
    void scalarMetricsMatchPriceChangeAndPassThroughPriceType(MarkPriceMetric selector, String expected) throws Exception {
        var service = service(data(selector.priceType(), true));
        var q = query(selector, "range_statistic", "1h");
        var result = new MarkPriceMetricProvider(service, market()).query(q, CONTEXT);
        var scalar = assertInstanceOf(MetricResult.Scalar.class, result);
        assertEquals(0, new BigDecimal(expected).compareTo(scalar.value()));
        assertEquals(new BigDecimal("7200.000"), scalar.observationSeconds());
        verify(service).query(CONTEXT.userZone(), q.time(), CONTEXT.requestTime(),
                Exchange.BINANCE, "BTC", selector.priceType(), CandleInterval.parse("1h"), false);
        var json = MetricResultJson.write(q.binding(), result);
        assertEquals(0, new BigDecimal(expected).compareTo(json.get("value").decimalValue()));
        assertEquals("mark_price", json.at("/source/provider").asText());
        assertEquals(selector.name(), json.at("/source/selector").asText());
        assertFalse(json.has("statistics"));
    }

    @Test void seriesProjectsSelectedPriceFromTypedCandles() throws Exception {
        var service = service(data(PriceType.MARK, true));
        var q = query(MarkPriceMetric.MARK_CLOSE, "time_series", null);
        var result = new MarkPriceMetricProvider(service, market()).query(q, CONTEXT);
        var series = assertInstanceOf(MetricResult.Series.class, result);
        assertEquals(2, series.samples().size());
        assertEquals(START, series.samples().get(0).time());
        assertEquals(0, new BigDecimal("104").compareTo(series.samples().get(0).value()));
        assertEquals(0, new BigDecimal("110").compareTo(series.samples().get(1).value()));
        verify(service).query(any(), any(), any(), any(), anyString(), eq(PriceType.MARK), isNull(), eq(false));
        var json = MetricResultJson.write(q.binding(), result);
        assertEquals("automatic", json.path("interval_source").asText());
        assertEquals("1h", json.path("candle_interval").asText());
    }

    @Test void incompleteRangeOmitsValueAndSeriesShapeIsRejectedForChangePct() throws Exception {
        var provider = new MarkPriceMetricProvider(service(data(PriceType.INDEX, false)), market());
        var q = query(MarkPriceMetric.INDEX_CLOSE, "range_statistic", null);
        var result = provider.query(q, CONTEXT);
        assertInstanceOf(MetricResult.Omitted.class, result);
        assertFalse(MetricResultJson.write(q.binding(), result).has("value"));
        assertThrows(IllegalArgumentException.class, () -> query(MarkPriceMetric.MARK_CHANGE_PCT, "time_series", null));
        assertThrows(IllegalArgumentException.class, () -> query(MarkPriceMetric.INDEX_CHANGE_PCT, "time_series", null));
    }

    @Test void snapshotUsesEachPriceOwnSourceTime() {
        for (var entry : java.util.Map.of(MarkPriceSnapshot.MARK, "65231.50",
                MarkPriceSnapshot.INDEX, "65210.30").entrySet()) {
            var binding = snapshotBinding(entry.getKey());
            var point = assertInstanceOf(MetricResult.Point.class,
                    new MarkPriceMetricProvider(mock(MarkPriceQueryService.class), market())
                            .query(new SnapshotQuery(binding), CONTEXT));
            assertEquals(0, new BigDecimal(entry.getValue()).compareTo(point.value()), entry.getKey().name());
            var json = MetricResultJson.write(binding, point);
            assertEquals(entry.getKey() == MarkPriceSnapshot.MARK ? "2026-09-28 22:00:00" : "2026-09-28 22:01:00",
                    json.path("as_of").asText(), "标记价与指数价各自带自己的来源时间");
            assertFalse(json.has("coverage"));
            assertEquals("mark_price", json.at("/source/provider").asText());
        }
    }

    @Test void snapshotRejectsRangeSelectorMissingValueAndMissingTime() {
        var provider = new MarkPriceMetricProvider(mock(MarkPriceQueryService.class), market());
        // 区间选择器不能用于快照形态：绑定构造期即拒绝
        assertThrows(IllegalArgumentException.class, () -> snapshotBindingRangeSelector());
        var noValue = mock(MarketDataService.class);
        when(noValue.markPrice(any(), anyString())).thenReturn(new com.cexpilot.market.model.MarkPrice(
                null, null, null, 0L, SNAPSHOT_TIME, SNAPSHOT_TIME));
        var omitted = new MarkPriceMetricProvider(mock(MarkPriceQueryService.class), noValue)
                .query(new SnapshotQuery(snapshotBinding(MarkPriceSnapshot.MARK)), CONTEXT);
        assertInstanceOf(MetricResult.Omitted.class, omitted);
        assertTrue(MetricResultJson.write(snapshotBinding(MarkPriceSnapshot.MARK), omitted)
                .path("statistics_omitted").asText().contains("未提供"));
        var noTime = mock(MarketDataService.class);
        when(noTime.markPrice(any(), anyString())).thenReturn(new com.cexpilot.market.model.MarkPrice(
                new BigDecimal("1"), new BigDecimal("1"), null, 0L, 0L, 0L));
        var error = assertThrows(IllegalArgumentException.class,
                () -> new MarkPriceMetricProvider(mock(MarkPriceQueryService.class), noTime)
                        .query(new SnapshotQuery(snapshotBinding(MarkPriceSnapshot.MARK)), CONTEXT));
        assertTrue(error.getMessage().contains("数据时间"), error.getMessage());
    }

    @Test void unknownSelectorIsRejectedByName() {
        var provider = new MarkPriceMetricProvider(mock(MarkPriceQueryService.class), market());
        assertEquals(MarkPriceSnapshot.MARK, provider.selector("MARK"));
        assertEquals(MarkPriceMetric.MARK_CLOSE, provider.selector("MARK_CLOSE"));
        assertThrows(IllegalArgumentException.class, () -> provider.selector("BASIS"));
    }

    private static MetricBinding snapshotBinding(MarkPriceSnapshot selector) {
        return new MetricBinding("m1", "mark.price", "binance", QueryShape.SNAPSHOT.code(), instrument(),
                "USDT", MarkPriceMetricProvider.NAME, selector, "test-v1");
    }

    private static MetricBinding snapshotBindingRangeSelector() {
        return new MetricBinding("m1", "mark.close", "binance", QueryShape.SNAPSHOT.code(), instrument(),
                "USDT", MarkPriceMetricProvider.NAME, MarkPriceMetric.MARK_CLOSE, "test-v1");
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode instrument() {
        return JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
    }

    private static MarketDataService market() {
        var market = mock(MarketDataService.class);
        when(market.markPrice(any(), anyString())).thenReturn(new com.cexpilot.market.model.MarkPrice(
                new BigDecimal("65231.50"), new BigDecimal("65210.30"), new BigDecimal("0.0001"),
                1700035200000L, SNAPSHOT_TIME, SNAPSHOT_TIME + 60_000));
        return market;
    }
}
