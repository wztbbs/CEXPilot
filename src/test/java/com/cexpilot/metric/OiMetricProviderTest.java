package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.oi.OiQueryRequest;
import com.cexpilot.market.oi.OiQueryResult;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.OiInterval;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** OI Provider + 假查询结果；统计口径对照 MarketCalculator.oiStats，不访问网络。 */
class OiMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-28T00:00:00Z");
    private static final RequestContext CONTEXT = new RequestContext(ZoneOffset.UTC, START.plusSeconds(86400));
    private static final TimeRange RANGE = new TimeRange(START, START.plusSeconds(7200), ZoneOffset.UTC);
    /** 快照数据时间；CONTEXT 时区为 UTC，渲染结果应为 22:00:00。 */
    private static final long SNAPSHOT_TIME = Instant.parse("2026-09-28T22:00:00Z").toEpochMilli();

    private static TimeRangeQuery query(OiMetric selector, String shape, String intervalCode, String unit) throws Exception {
        var instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        var binding = new MetricBinding("m1", "oi.test", "binance", shape, instrument, unit,
                OiMetricProvider.NAME, selector, "test-v1");
        return new TimeRangeQuery(binding, TimeSpecParser.parse(JSON.readTree(
                "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"full\",\"extent\":\"full_period\"}")),
                intervalCode, false);
    }

    private static OiQueryResult data(boolean complete, BigDecimal firstOi) {
        var points = List.of(
                new OiPoint(START.toEpochMilli(), firstOi),
                new OiPoint(START.plusSeconds(3600).toEpochMilli(), new BigDecimal("1100")));
        var request = new OiQueryRequest(Exchange.BINANCE, "BTC", OiInterval.parse("1h"), RANGE, false);
        // INSTANT 语义：覆盖终点是末个采样点时刻
        var coverage = new SeriesCoverage(2, 2, complete ? List.of() : List.of(START.toEpochMilli()),
                List.of(), List.of(), false, false, null, START.plusSeconds(3600).toEpochMilli(), false);
        return new OiQueryResult(request, request, points, coverage);
    }

    private static OiQueryService service(OiQueryResult result) {
        var service = mock(OiQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString(), any(), anyBoolean())).thenReturn(result);
        return service;
    }

    @ParameterizedTest
    @CsvSource({"START,1000", "END,1100", "CHANGE,100", "CHANGE_PCT,10.0000", "MIN,1000", "MAX,1100"})
    void scalarMetricsMatchOiStatsAndCarryObservationWindow(OiMetric selector, String expected) throws Exception {
        var service = service(data(true, new BigDecimal("1000")));
        var q = query(selector, "range_statistic", "30m", selector == OiMetric.CHANGE_PCT ? "percent" : "BTC");
        var result = new OiMetricProvider(service, market()).query(q, CONTEXT);
        var scalar = assertInstanceOf(MetricResult.Scalar.class, result);
        assertEquals(0, new BigDecimal(expected).compareTo(scalar.value()));
        assertEquals(new BigDecimal("3600.000"), scalar.observationSeconds());
        assertEquals(START, scalar.actualRange().startInclusive());
        assertEquals(START.plusSeconds(3600), scalar.actualRange().endExclusive());
        verify(service).query(CONTEXT.userZone(), q.time(), CONTEXT.requestTime(),
                Exchange.BINANCE, "BTC", OiInterval.parse("30m"), false);
        var json = MetricResultJson.write(q.binding(), result);
        assertEquals(0, new BigDecimal(expected).compareTo(json.get("value").decimalValue()));
        assertEquals("30m", json.path("candle_interval").asText());
        assertEquals("explicit", json.path("interval_source").asText());
        assertEquals(2, json.path("candle_count").asInt());
        assertEquals("oi", json.at("/source/provider").asText());
        assertEquals(selector.name(), json.at("/source/selector").asText());
    }

    @Test void seriesProjectsEveryPointAndDefaultsToOneHourInterval() throws Exception {
        var service = service(data(true, new BigDecimal("1000")));
        var q = query(OiMetric.QUANTITY, "time_series", null, "BTC");
        var result = new OiMetricProvider(service, market()).query(q, CONTEXT);
        var series = assertInstanceOf(MetricResult.Series.class, result);
        assertEquals(2, series.samples().size());
        assertEquals(START, series.samples().get(0).time());
        assertEquals(0, new BigDecimal("1100").compareTo(series.samples().get(1).value()));
        verify(service).query(any(), any(), any(), any(), anyString(), eq(OiInterval.parse("1h")), eq(false));
        var json = MetricResultJson.write(q.binding(), result);
        assertEquals("1h", json.path("candle_interval").asText());
        assertEquals("automatic", json.path("interval_source").asText());
        assertFalse(json.has("value"));
        assertEquals(2, json.path("samples").size());
    }

    @Test void incompleteRangeOmitsValueAndZeroStartRejectsChangePct() throws Exception {
        var provider = new OiMetricProvider(service(data(false, new BigDecimal("1000"))), market());
        var q = query(OiMetric.END, "range_statistic", null, "BTC");
        var result = provider.query(q, CONTEXT);
        assertInstanceOf(MetricResult.Omitted.class, result);
        var json = MetricResultJson.write(q.binding(), result);
        assertFalse(json.has("value"));
        assertFalse(json.at("/coverage/range_complete").asBoolean());
        // 期初为 0 时 change_pct 无法定义，按缺失处理而不是输出 0
        var zeroStart = new OiMetricProvider(service(data(true, BigDecimal.ZERO)), market());
        assertThrows(IllegalArgumentException.class,
                () -> zeroStart.query(query(OiMetric.CHANGE_PCT, "range_statistic", null, "percent"), CONTEXT));
    }

    @Test void shapeSupportIsDeclaredBySelector() {
        assertThrows(IllegalArgumentException.class, () -> query(OiMetric.QUANTITY, "range_statistic", null, "BTC"));
        assertThrows(IllegalArgumentException.class, () -> query(OiMetric.START, "time_series", null, "BTC"));
        assertTrue(OiMetric.QUANTITY.supports(QueryShape.TIME_SERIES.code()));
        assertTrue(OiMetric.QUANTITY.supports(QueryShape.SNAPSHOT.code()));
        assertFalse(OiMetric.QUANTITY.supports(QueryShape.RANGE_STATISTIC.code()));
        assertFalse(OiMetric.END.supports(QueryShape.SNAPSHOT.code()));
    }

    @Test void snapshotReturnsCurrentQuantityWithDataTime() {
        var binding = snapshotBinding(OiMetric.QUANTITY, "BTC");
        var point = assertInstanceOf(MetricResult.Point.class,
                new OiMetricProvider(mock(OiQueryService.class), market())
                        .query(new SnapshotQuery(binding), CONTEXT));
        assertEquals(0, new BigDecimal("104091.102").compareTo(point.value()));
        var json = MetricResultJson.write(binding, point);
        assertEquals("2026-09-28 22:00:00", json.path("as_of").asText());
        assertFalse(json.has("requested_range"));
        assertFalse(json.has("coverage"));
        assertEquals("oi", json.at("/source/provider").asText());
    }

    @Test void snapshotRejectsNonQuantitySelectorAndMissingDataTime() {
        var provider = new OiMetricProvider(mock(OiQueryService.class), market());
        assertThrows(IllegalArgumentException.class,
                () -> provider.query(new SnapshotQuery(snapshotBinding(OiMetric.END, "BTC")), CONTEXT));
        var noTime = mock(MarketDataService.class);
        when(noTime.oiSnapshot(any(), anyString()))
                .thenReturn(new com.cexpilot.market.model.OpenInterestInfo(new BigDecimal("100"), "BTC", 0L, 0L));
        var error = assertThrows(IllegalArgumentException.class, () -> new OiMetricProvider(mock(OiQueryService.class), noTime)
                .query(new SnapshotQuery(snapshotBinding(OiMetric.QUANTITY, "BTC")), CONTEXT));
        assertTrue(error.getMessage().contains("数据时间"), error.getMessage());
    }

    private static MetricBinding snapshotBinding(OiMetric selector, String unit) {
        var instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        return new MetricBinding("m1", "oi.quantity", "binance", QueryShape.SNAPSHOT.code(), instrument, unit,
                OiMetricProvider.NAME, selector, "test-v1");
    }

    private static MarketDataService market() {
        var market = mock(MarketDataService.class);
        when(market.oiSnapshot(any(), anyString()))
                .thenReturn(new com.cexpilot.market.model.OpenInterestInfo(
                        new BigDecimal("104091.102"), "BTC", SNAPSHOT_TIME, SNAPSHOT_TIME));
        return market;
    }
}
