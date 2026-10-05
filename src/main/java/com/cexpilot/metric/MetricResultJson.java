package com.cexpilot.metric;

import com.cexpilot.market.Times;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.util.List;

/** 唯一的指标结果序列化边界：保留 value/samples 引用路径和既有时间、覆盖信息。 */
public final class MetricResultJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int OPEN_TIME_PREVIEW_LIMIT = 20;
    private MetricResultJson() {}

    static ObjectNode identity(MetricBinding binding) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("group_id", binding.groupId()).put("metric", binding.metric()).put("exchange", binding.exchange())
                .put("query_shape", binding.shape()).put("unit", binding.unit()).put("catalog_version", binding.catalogVersion());
        out.set("instrument", binding.instrument().deepCopy());
        out.putObject("source").put("provider", binding.provider()).put("selector", binding.selector().name());
        return out;
    }

    public static ObjectNode write(MetricBinding binding, MetricResult result) {
        ObjectNode out = identity(binding);
        var metadata = result.metadata();
        ZoneId zone = metadata.effectiveRange().timezone();
        out.set("requested_range", range(metadata.requestedRange()));
        if (!metadata.effectiveRange().equals(metadata.requestedRange())) out.set("effective_range", range(metadata.effectiveRange()));
        out.set("coverage", coverage(metadata.coverage(), zone));
        out.put("candle_interval", metadata.interval().code());
        out.put("interval_source", metadata.automaticInterval() ? "automatic" : "explicit");
        out.put("estimated", false).put("truncated", false);
        if (result instanceof MetricResult.Scalar scalar) {
            out.put("candle_count", metadata.candleCount());
            out.put("value", scalar.value());
            out.put("observation_seconds", scalar.observationSeconds());
            ObjectNode actual = range(scalar.actualRange());
            actual.remove("timezone"); // 兼容现有 actual_range 的字段结构
            out.set("actual_range", actual);
        } else if (result instanceof MetricResult.Omitted omitted) {
            out.put("statistics_omitted", omitted.reason());
        } else if (result instanceof MetricResult.Series series) {
            out.put("candle_count", metadata.candleCount());
            var samples = out.putArray("samples");
            for (var sample : series.samples()) {
                samples.addObject().put("time", Times.readable(sample.time().toEpochMilli(), zone)).put("value", sample.value());
            }
        }
        return out;
    }

    private static ObjectNode range(TimeRange range) {
        return MAPPER.createObjectNode().put("start_inclusive", Times.readable(range.startInclusive().toEpochMilli(), range.timezone()))
                .put("end_exclusive", Times.readable(range.endExclusive().toEpochMilli(), range.timezone()))
                .put("timezone", range.timezone().getId());
    }

    private static ObjectNode coverage(SeriesCoverage coverage, ZoneId zone) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("expected_count", coverage.expectedCount()).put("actual_count", coverage.actualCount())
                .put("closed_part_complete", coverage.complete()).put("range_complete", coverage.rangeComplete());
        if (coverage.coveredUntilMs() != null) out.put("covered_until", Times.readable(coverage.coveredUntilMs(), zone));
        out.put("missing_count", coverage.missing().size());
        times(out, "missing_open_times", coverage.missing(), zone);
        out.put("unexpected_count", coverage.unexpected().size());
        times(out, "unexpected_open_times", coverage.unexpected(), zone);
        out.put("contains_unclosed", coverage.containsUnclosed()).put("dropped_unclosed", coverage.droppedUnclosed());
        if (coverage.abortReason() != null) out.put("abort_reason", coverage.abortReason());
        return out;
    }
    private static void times(ObjectNode out, String field, List<Long> values, ZoneId zone) {
        var array = out.putArray(field);
        values.stream().limit(OPEN_TIME_PREVIEW_LIMIT).forEach(t -> array.add(Times.readable(t, zone)));
    }

    /** 元数据不开放为任意计算输入，引用白名单与结果模型分别维护。 */
    static JsonNode outputSchema(MetricBinding binding) {
        ObjectNode schema = objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        if (binding.isRangeStatistic()) {
            properties.putObject("value").put("type", "number");
            properties.putObject("observation_seconds").put("type", "number");
        } else {
            ObjectNode array = properties.putObject("samples").put("type", "array");
            ObjectNode item = objectSchema();
            ((ObjectNode) item.get("properties")).putObject("time").put("type", "string");
            ((ObjectNode) item.get("properties")).putObject("value").put("type", "number");
            array.set("items", item);
        }
        return schema;
    }
    private static ObjectNode objectSchema() {
        ObjectNode result = MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        result.putObject("properties");
        return result;
    }
}
