package com.cexpilot.metric;

import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 锁定指标结果 JSON 的键结构：序列化边界改动必须逐键等价。 */
class MetricResultJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-28T00:00:00Z");
    private static final TimeRange RANGE = new TimeRange(START, START.plusSeconds(7200), ZoneOffset.UTC);

    private static MetricBinding binding(String shape) {
        var instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        return new MetricBinding("m1", "price.close", "binance", shape, instrument, "USDT",
                KlineMetricProvider.NAME, KlineMetric.CLOSE, "test-v1");
    }

    private static MetricResult.SeriesMetadata metadata() {
        var coverage = new SeriesCoverage(2, 2, List.of(), List.of(), List.of(), false, false, null,
                RANGE.endExclusive().toEpochMilli(), false);
        return new MetricResult.SeriesMetadata(RANGE, RANGE, coverage, "1h", false, 2);
    }

    private static List<String> keys(ObjectNode node) {
        List<String> keys = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test void scalarWritesFullKeySetInStableOrder() {
        var result = new MetricResult.Scalar(metadata(), new BigDecimal("101.25"),
                new BigDecimal("7200.000"), RANGE);
        ObjectNode json = MetricResultJson.write(binding("range_statistic"), result);
        assertEquals(List.of("group_id", "metric", "exchange", "query_shape", "unit", "catalog_version",
                "instrument", "source", "requested_range", "coverage", "candle_interval", "interval_source",
                "estimated", "truncated", "candle_count", "value", "observation_seconds", "actual_range"), keys(json));
        assertFalse(json.path("estimated").asBoolean());
        assertFalse(json.path("truncated").asBoolean());
        assertEquals("explicit", json.path("interval_source").asText());
        assertEquals("1h", json.path("candle_interval").asText());
        assertEquals(2, json.path("candle_count").asInt());
        assertEquals(List.of("start_inclusive", "end_exclusive"), keys((ObjectNode) json.get("actual_range")));
        assertEquals(List.of("expected_count", "actual_count", "closed_part_complete", "range_complete",
                "covered_until", "missing_count", "missing_open_times", "unexpected_count",
                "unexpected_open_times", "contains_unclosed", "dropped_unclosed"), keys((ObjectNode) json.get("coverage")));
    }

    @Test void seriesAndOmittedKeepTheirVariantKeys() {
        var series = new MetricResult.Series(metadata(),
                List.of(new MetricResult.Sample(START, new BigDecimal("100"))));
        ObjectNode seriesJson = MetricResultJson.write(binding("time_series"), series);
        assertEquals(List.of("group_id", "metric", "exchange", "query_shape", "unit", "catalog_version",
                "instrument", "source", "requested_range", "coverage", "candle_interval", "interval_source",
                "estimated", "truncated", "candle_count", "samples"), keys(seriesJson));
        assertEquals(List.of("time", "value"), keys((ObjectNode) seriesJson.get("samples").get(0)));

        var omitted = new MetricResult.Omitted(metadata(), "区间未完整覆盖，不提供指标 value");
        ObjectNode omittedJson = MetricResultJson.write(binding("range_statistic"), omitted);
        assertEquals(List.of("group_id", "metric", "exchange", "query_shape", "unit", "catalog_version",
                "instrument", "source", "requested_range", "coverage", "candle_interval", "interval_source",
                "estimated", "truncated", "statistics_omitted"), keys(omittedJson));
    }

    @Test void nonSeriesMetadataOmitsSeriesSpecificKeysButKeepsQualityFlags() {
        MetricResult.Metadata plain = new MetricResult.Metadata() {
            public TimeRange requestedRange() { return RANGE; }
            public TimeRange effectiveRange() { return RANGE; }
        };
        var result = new MetricResult.Scalar(plain, BigDecimal.ONE, new BigDecimal("7200.000"), RANGE);
        ObjectNode json = MetricResultJson.write(binding("range_statistic"), result);
        for (String key : List.of("coverage", "candle_interval", "interval_source", "candle_count")) {
            assertFalse(json.has(key), key);
        }
        assertTrue(json.has("requested_range"));
        assertFalse(json.path("estimated").asBoolean());
        assertFalse(json.path("truncated").asBoolean());
        assertEquals(0, BigDecimal.ONE.compareTo(json.get("value").decimalValue()));
    }
}
