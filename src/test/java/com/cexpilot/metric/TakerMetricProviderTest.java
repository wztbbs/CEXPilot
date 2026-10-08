package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.market.taker.TakerVolumeQueryResult;
import com.cexpilot.market.taker.TakerVolumeQueryService;
import com.cexpilot.runtime.RequestContext;
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

/** taker Provider + 假查询结果；统计口径对照 MarketCalculator.takerFlowStats，固定 5m 聚合。 */
class TakerMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-28T00:00:00Z");
    private static final RequestContext CONTEXT = new RequestContext(ZoneOffset.UTC, START.plusSeconds(86400));
    private static final TimeRange RANGE = new TimeRange(START, START.plusSeconds(600), ZoneOffset.UTC);

    private static TimeRangeQuery query(TakerMetric selector) throws Exception {
        var instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        var binding = new MetricBinding("m1", "taker.test", "binance", "range_statistic", instrument,
                selector == TakerMetric.BUY_RATIO ? "ratio" : "BTC", TakerMetricProvider.NAME, selector, "test-v1");
        return new TimeRangeQuery(binding, TimeSpecParser.parse(JSON.readTree(
                "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"full\",\"extent\":\"full_period\"}")),
                null, false);
    }

    private static TakerVolumeQueryResult data(boolean complete, boolean zeroVolume) {
        var points = List.of(
                new TakerVolumePoint(START.toEpochMilli(),
                        zeroVolume ? BigDecimal.ZERO : new BigDecimal("10"), zeroVolume ? BigDecimal.ZERO : new BigDecimal("5")),
                new TakerVolumePoint(START.plusSeconds(300).toEpochMilli(),
                        zeroVolume ? BigDecimal.ZERO : new BigDecimal("20"), zeroVolume ? BigDecimal.ZERO : new BigDecimal("15")));
        // PERIOD 语义：覆盖终点是末个周期点 + 5m
        var coverage = new SeriesCoverage(2, 2, complete ? List.of() : List.of(START.toEpochMilli()),
                List.of(), List.of(), false, false, null, START.plusSeconds(600).toEpochMilli(), false);
        return new TakerVolumeQueryResult(RANGE, RANGE, points, coverage);
    }

    private static TakerVolumeQueryService service(TakerVolumeQueryResult result) {
        var service = mock(TakerVolumeQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString())).thenReturn(result);
        return service;
    }

    @ParameterizedTest
    @CsvSource({"BUY_VOLUME,30", "SELL_VOLUME,20", "BUY_RATIO,0.6000"})
    void scalarMetricsMatchTakerFlowStatsWithFixedFiveMinuteGrid(TakerMetric selector, String expected) throws Exception {
        var service = service(data(true, false));
        var q = query(selector);
        var result = new TakerMetricProvider(service).query(q, CONTEXT);
        var scalar = assertInstanceOf(MetricResult.Scalar.class, result);
        assertEquals(0, new BigDecimal(expected).compareTo(scalar.value()));
        assertEquals(new BigDecimal("600.000"), scalar.observationSeconds());
        verify(service).query(CONTEXT.userZone(), q.time(), CONTEXT.requestTime(), Exchange.BINANCE, "BTC");
        var json = MetricResultJson.write(q.binding(), result);
        assertEquals(0, new BigDecimal(expected).compareTo(json.get("value").decimalValue()));
        assertEquals("5m", json.path("candle_interval").asText());
        assertEquals("automatic", json.path("interval_source").asText());
        assertEquals(selector == TakerMetric.BUY_RATIO ? "ratio" : "BTC", json.path("unit").asText());
        assertEquals("taker", json.at("/source/provider").asText());
    }

    @Test void incompleteRangeOmitsValueAndZeroTotalRejectsRatio() throws Exception {
        var provider = new TakerMetricProvider(service(data(false, false)));
        var q = query(TakerMetric.BUY_VOLUME);
        var result = provider.query(q, CONTEXT);
        assertInstanceOf(MetricResult.Omitted.class, result);
        assertFalse(MetricResultJson.write(q.binding(), result).has("value"));
        // 总量为 0 时占比无法定义，按缺失处理而不是输出 0
        var zero = new TakerMetricProvider(service(data(true, true)));
        assertThrows(IllegalArgumentException.class, () -> zero.query(query(TakerMetric.BUY_RATIO), CONTEXT));
    }

    @Test void timeSeriesShapeIsRejected() {
        assertFalse(TakerMetric.BUY_VOLUME.supports(QueryShape.TIME_SERIES.code()));
        assertThrows(IllegalArgumentException.class, () -> {
            var instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                    .put("quote", "USDT").put("settle", "USDT");
            new MetricBinding("m1", "taker.test", "binance", "time_series", instrument, "BTC",
                    TakerMetricProvider.NAME, TakerMetric.BUY_VOLUME, "test-v1");
        });
    }
}
