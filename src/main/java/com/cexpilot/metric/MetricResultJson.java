package com.cexpilot.metric;

import com.cexpilot.market.Times;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
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
        if (binding.description() != null && !binding.description().isBlank()) {
            out.put("description", binding.description());
        }
        out.set("instrument", binding.instrument().deepCopy());
        out.putObject("source").put("provider", binding.provider()).put("selector", binding.selector().name());
        return out;
    }

    public static ObjectNode write(MetricBinding binding, MetricResult result) {
        ObjectNode out = identity(binding);
        var metadata = result.metadata();
        if (metadata.requestedRange() != null) {
            ZoneId zone = metadata.effectiveRange().timezone();
            out.set("requested_range", range(metadata.requestedRange()));
            if (!metadata.effectiveRange().equals(metadata.requestedRange())) out.set("effective_range", range(metadata.effectiveRange()));
            if (metadata instanceof MetricResult.SeriesMetadata series) {
                out.set("coverage", coverage(series.coverage(), zone));
                out.put("candle_interval", series.interval());
                out.put("interval_source", series.automaticInterval() ? "automatic" : "explicit");
            }
        } else if (metadata instanceof MetricResult.SnapshotMetadata snapshot) {
            // 快照没有窗口，只有数据时间；时区由 Provider 在取数时从请求上下文带入。
            out.put("as_of", Times.readable(snapshot.asOf().toEpochMilli(), snapshot.zone()));
        } else if (metadata instanceof MetricResult.RecentMetadata recent) {
            out.put("timezone", recent.zone().getId());
            out.put("sample_scope", "recent_settlements");
            out.put("sample_complete_meaning", "仅表示取齐请求期数，不代表覆盖用户指定时间区间");
            out.put("requested_count", recent.requestedCount());
            out.put("actual_count", recent.actualCount());
            out.put("sample_complete", recent.sampleComplete());
            // 只有完整单期样本且周期可核实时才开放；缺周期不等于缺费率。
            if (recent.requestedCount() == 1 && recent.sampleComplete()) {
                if (recent.periodMs() != null && recent.periodMs() > 0) {
                    out.put("period_seconds", BigDecimal.valueOf(recent.periodMs(), 3));
                    out.put("period_source", "adjacent_settlement_times");
                    out.put("period_scope", "historical_settlement_only");
                } else {
                    out.put("period_unavailable_reason", "缺少相邻结算记录或时间间隔异常，无法核实该笔费率周期，不支持年化");
                }
            }
        }
        if (binding.selector() instanceof FundingMetric) {
            out.put("rate_status", "settled");
            out.put("next_settlement_time_available", false);
            out.put("next_settlement_time_unavailable_reason", "系统未接入下次结算时间；历史周期不能用于推算未来结算安排");
        }
        out.put("estimated", false).put("truncated", false);
        if (result instanceof MetricResult.Scalar scalar) {
            if (metadata instanceof MetricResult.SeriesMetadata series) out.put("candle_count", series.candleCount());
            out.put("value", scalar.value());
            out.put("observation_seconds", scalar.observationSeconds());
            ObjectNode actual = range(scalar.actualRange());
            actual.remove("timezone"); // 兼容现有 actual_range 的字段结构
            out.set("actual_range", actual);
        } else if (result instanceof MetricResult.Omitted omitted) {
            out.put("statistics_omitted", omitted.reason());
        } else if (result instanceof MetricResult.Series series) {
            if (metadata instanceof MetricResult.SeriesMetadata seriesMetadata) out.put("candle_count", seriesMetadata.candleCount());
            ZoneId zone = metadata instanceof MetricResult.RecentMetadata recent ? recent.zone()
                    : metadata.effectiveRange().timezone();
            var samples = out.putArray("samples");
            for (var sample : series.samples()) {
                var item = samples.addObject().put("time", Times.readable(sample.time().toEpochMilli(), zone)).put("value", sample.value());
                if (binding.selector() instanceof FundingMetric) {
                    item.put("time_iso", sample.time().atZone(zone).toOffsetDateTime().toString());
                    item.put("payment_direction", FundingMetric.paymentDirection(sample.value()));
                }
            }
        } else if (result instanceof MetricResult.Point point) {
            out.put("value", point.value());
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
        switch (QueryShape.from(binding.shape())) {
            case RANGE_STATISTIC -> {
                properties.putObject("value").put("type", "number");
                properties.putObject("observation_seconds").put("type", "number");
            }
            case SNAPSHOT, OFFICIAL_24H -> properties.putObject("value").put("type", "number");
            case TIME_SERIES -> {
                ObjectNode array = properties.putObject("samples").put("type", "array");
                ObjectNode item = objectSchema();
                ((ObjectNode) item.get("properties")).putObject("time").put("type", "string");
                ((ObjectNode) item.get("properties")).putObject("value").put("type", "number");
                array.set("items", item);
            }
            case RECENT_N -> {
                ObjectNode array = properties.putObject("samples").put("type", "array");
                ObjectNode item = objectSchema();
                ((ObjectNode) item.get("properties")).putObject("time").put("type", "string");
                ((ObjectNode) item.get("properties")).putObject("value").put("type", "number");
                array.set("items", item);
                properties.putObject("period_seconds").put("type", "number");
            }
        }
        return schema;
    }
    private static ObjectNode objectSchema() {
        ObjectNode result = MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        result.putObject("properties");
        return result;
    }
}
