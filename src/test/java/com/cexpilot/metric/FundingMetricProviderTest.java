package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.funding.FundingQueryService;
import com.cexpilot.market.funding.FundingRecentResult;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 资金费率 Provider + 假取样结果；不访问网络。 */
class FundingMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Instant FIRST = Instant.parse("2026-09-28T00:00:00Z");
    private static final long INTERVAL_MS = 8 * 3_600_000L;

    private static MetricBinding binding(FundingMetric selector) {
        ObjectNode instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        return new MetricBinding("m1", "funding.rate_settled", "binance", QueryShape.RECENT_N.code(), instrument,
                "ratio", FundingMetricProvider.NAME, selector, "test-v1");
    }

    private static FundingRecentResult sample(int count) {
        List<FundingRatePoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new FundingRatePoint(new BigDecimal("0.0001").multiply(BigDecimal.valueOf(i + 1)),
                    FIRST.plusSeconds(i * INTERVAL_MS / 1000).toEpochMilli()));
        }
        return new FundingRecentResult(points, INTERVAL_MS);
    }

    private static FundingQueryService service(FundingRecentResult result) {
        var service = mock(FundingQueryService.class);
        when(service.queryRecent(any(Exchange.class), anyString(), anyInt(), any(Instant.class))).thenReturn(result);
        return service;
    }

    @Test void recentSamplesCarrySettlementTimeAndCounts() {
        var binding = binding(FundingMetric.RATE);
        var query = new CountQuery(binding, 10);
        var service = service(sample(10));
        var series = assertInstanceOf(MetricResult.Series.class,
                new FundingMetricProvider(service).query(query, new RequestContext(ZoneOffset.UTC, NOW)));
        assertEquals(10, series.samples().size());
        assertEquals(FIRST, series.samples().get(0).time());
        assertEquals(0, new BigDecimal("0.0001").compareTo(series.samples().get(0).value()));
        var json = MetricResultJson.write(binding, series);
        assertEquals(10, json.path("requested_count").asInt());
        assertEquals(10, json.path("actual_count").asInt());
        assertTrue(json.path("sample_complete").asBoolean());
        assertEquals("2026-09-28 00:00:00", json.at("/samples/0/time").asText());
        assertEquals("funding", json.at("/source/provider").asText());
        assertFalse(json.has("coverage"));
        assertFalse(json.has("requested_range"));
        // 完整样本可以进算子
        assertDoesNotThrow(() -> new com.cexpilot.calculation.AvgTool().validateSource(json));
        verify(service).queryRecent(Exchange.BINANCE, "BTC", 10, NOW);
    }

    @Test void shortSampleIsMarkedIncompleteAndBlocksCalculation() {
        var binding = binding(FundingMetric.RATE);
        var series = (MetricResult.Series) new FundingMetricProvider(service(sample(7)))
                .query(new CountQuery(binding, 10), new RequestContext(ZoneOffset.UTC, NOW));
        var json = MetricResultJson.write(binding, series);
        assertEquals(7, json.path("actual_count").asInt());
        assertFalse(json.path("sample_complete").asBoolean());
        var error = assertThrows(IllegalArgumentException.class,
                () -> new com.cexpilot.calculation.AvgTool().validateSource(json));
        assertTrue(error.getMessage().contains("不完整"), error.getMessage());
    }

    @Test void emptySampleIsOmittedNotZero() {
        var binding = binding(FundingMetric.RATE);
        var omitted = assertInstanceOf(MetricResult.Omitted.class,
                new FundingMetricProvider(service(new FundingRecentResult(List.of(), INTERVAL_MS)))
                        .query(new CountQuery(binding, 1), new RequestContext(ZoneOffset.UTC, NOW)));
        assertTrue(omitted.reason().contains("未取到"), omitted.reason());
        assertFalse(MetricResultJson.write(binding, omitted).has("samples"));
    }

    @Test void countBoundsAndShapeAreEnforced() {
        var binding = binding(FundingMetric.RATE);
        for (int invalid : new int[]{0, 101, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new CountQuery(binding, invalid));
        }
        assertFalse(FundingMetric.RATE.supports(QueryShape.SNAPSHOT.code()));
        assertFalse(FundingMetric.RATE.supports(QueryShape.RANGE_STATISTIC.code()));
        assertTrue(FundingMetric.RATE.supports(QueryShape.RECENT_N.code()));
        var provider = new FundingMetricProvider(service(sample(3)));
        assertThrows(IllegalArgumentException.class, () -> provider.query(
                new SnapshotQuery(binding, null), new RequestContext(ZoneOffset.UTC, NOW)));
    }

    @Test void samplesRenderInRequestZone() {
        var binding = binding(FundingMetric.RATE);
        var series = (MetricResult.Series) new FundingMetricProvider(service(sample(2)))
                .query(new CountQuery(binding, 2), new RequestContext(ZoneId.of("Asia/Shanghai"), NOW));
        assertEquals("2026-09-28 08:00:00",
                MetricResultJson.write(binding, series).at("/samples/0/time").asText());
    }
}
