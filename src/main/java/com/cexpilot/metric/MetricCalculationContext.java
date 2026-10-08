package com.cexpilot.metric;

import com.cexpilot.dag.DagContext;
import com.cexpilot.dag.PlanNode;
import com.cexpilot.dag.ReferenceResolver;
import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 数字引用之外保留有限、去重的指标来源；不把时间/业务语义丢在上一层。 */
public final class MetricCalculationContext {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SAME_UNIT = Set.of("avg", "sum", "min", "max", "compare", "difference", "ratio", "relative_change");

    /**
     * 行情年化的合法输入规则：指标 + 引用路径 + basis + rate_unit。
     * 单期费率还要求 count=1（否则 samples.0 是最旧一期，不是"最近一期"），
     * 两条路径的周期都必须引用同一结果的秒数，不接受默认 8 小时或中间计算结果。
     */
    private record AnnualizeInput(String metric, String refPath, String basis, String rateUnit, boolean singlePeriod) {}
    private static final List<AnnualizeInput> ANNUALIZE_INPUTS = List.of(
            new AnnualizeInput("price.change_pct", ".data.value", "holding_return", "percent", false),
            new AnnualizeInput("funding.rate_settled", ".data.samples.0.value", "periodic_rate", "ratio", true));

    private MetricCalculationContext() {}

    public static void validate(PlanNode node, DagContext context) {
        Set<String> units = units(node, context);
        if (SAME_UNIT.contains(node.tool()) && units.size() > 1) {
            throw new IllegalArgumentException("计算输入单位不一致: " + units);
        }
        if ("annualize".equals(node.tool())) {
            validateAnnualize(node, context);
        }
    }

    private static void validateAnnualize(PlanNode node, DagContext context) {
        JsonNode input = node.args().path("input");
        List<ReferenceResolver.Ref> rateRefs = ReferenceResolver.findRefs(input.path("rate"));
        if (rateRefs.isEmpty()) {
            // 用户显式提供的收益率和周期常量仍可计算；不能拼接行情周期或其他节点参数。
            if (!ReferenceResolver.findRefs(input).isEmpty()) {
                throw new IllegalArgumentException("常量年化须同时提供明确的收益率和周期常量，不接受混用节点引用");
            }
            return;
        }
        if (rateRefs.size() != 1) throw new IllegalArgumentException("年化 rate 必须直接引用一个受支持的指标值");
        ReferenceResolver.Ref rateRef = rateRefs.get(0);
        ToolResult source = context.get(rateRef.nodeId());
        JsonNode data = source == null ? null : source.data();
        if (data == null || !data.has("metric")) {
            throw new IllegalArgumentException("年化 rate 必须直接引用受支持的原始指标，不接受 avg/sum 等中间计算结果");
        }
        AnnualizeInput rule = annualizeRule(data.path("metric").asText(), rateRef.path(), input);
        if (rule == null) {
            throw new IllegalArgumentException("年化只支持：price.change_pct 的 value（basis=holding_return，rate_unit=percent）"
                    + "，或 funding.rate_settled 单期的 samples.0.value（count=1，basis=periodic_rate，rate_unit=ratio）");
        }
        if (rule.singlePeriod()) {
            if (!"recent_n".equals(data.path("query_shape").asText())
                    || data.path("requested_count").asInt() != 1
                    || data.path("samples").size() != 1
                    || !data.path("sample_complete").asBoolean(false)) {
                throw new IllegalArgumentException("单期费率年化只能引用 count=1 的完整取样结果；多期 samples.0 不是最近一期");
            }
        } else if (!"range_statistic".equals(data.path("query_shape").asText())) {
            throw new IllegalArgumentException("价格收益率年化必须使用 range_statistic 指标");
        }
        String periodField = rule.singlePeriod() ? "period_seconds" : "observation_seconds";
        requireSameSourcePeriod(rateRef, input.path("period"), periodField);
        JsonNode duration = data.path(periodField);
        if (!duration.isNumber() || duration.decimalValue().signum() <= 0) {
            throw new IllegalArgumentException("年化缺少可核实的正数周期 " + periodField + "，不能使用默认周期外推");
        }
    }

    /** 两条指标年化路径都使用同源的秒数；校验引用位置和单位，不能只检查出现过某个引用。 */
    private static void requireSameSourcePeriod(ReferenceResolver.Ref rateRef, JsonNode period, String field) {
        String expected = "{{" + rateRef.nodeId() + ".data." + field + "}}";
        if (!expected.equals(period.path("value").asText()) || !"second".equals(period.path("unit").asText())) {
            throw new IllegalArgumentException("年化 period.value 必须引用同一结果的 " + field
                    + "，且 period.unit 必须为 second；不接受周期常量、跨结果周期或其他单位");
        }
    }

    private static AnnualizeInput annualizeRule(String metric, String refPath, JsonNode input) {
        for (AnnualizeInput rule : ANNUALIZE_INPUTS) {
            if (rule.metric().equals(metric) && rule.refPath().equals(refPath)
                    && rule.basis().equals(input.path("basis").asText())
                    && rule.rateUnit().equals(input.path("rate_unit").asText())) {
                return rule;
            }
        }
        return null;
    }

    public static ToolResult attach(PlanNode node, DagContext context, ToolResult result) {
        if (!result.ok()) return result;
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        for (String dep : node.dependsOn()) {
            JsonNode data = context.get(dep).data();
            if (data == null) continue;
            if (data.has("metric")) {
                ObjectNode metadata = MAPPER.createObjectNode().put("node_id", dep);
                for (String key : List.of("group_id", "metric", "exchange", "instrument", "unit", "query_shape",
                        "requested_range", "effective_range", "actual_range", "coverage", "candle_interval",
                        "catalog_version", "estimated", "truncated", "source",
                        "timezone", "sample_scope", "sample_complete_meaning", "period_scope",
                        "rate_status", "next_settlement_time_available", "next_settlement_time_unavailable_reason",
                        "requested_count", "actual_count", "sample_complete", "period_seconds", "period_source", "period_unavailable_reason")) {
                    if (data.has(key)) metadata.set(key, data.get(key).deepCopy());
                }
                sources.put(dep, metadata);
            }
            for (JsonNode source : data.path("metric_sources")) sources.put(source.path("node_id").asText(), source);
        }
        if (sources.isEmpty()) return result;
        ObjectNode data = result.data().deepCopy();
        var array = data.putArray("metric_sources");
        sources.values().forEach(source -> array.add(source.deepCopy()));
        Set<String> units = units(node, context);
        if (Set.of("avg", "sum", "min", "max", "difference").contains(node.tool()) && units.size() == 1) {
            data.put("unit", units.iterator().next());
        } else if (Set.of("ratio", "relative_change", "annualize").contains(node.tool())) {
            data.put("unit", "ratio");
        }
        return ToolResult.success(data);
    }

    private static Set<String> units(PlanNode node, DagContext context) {
        Set<String> units = new LinkedHashSet<>();
        for (ReferenceResolver.Ref ref : ReferenceResolver.findRefs(node.args())) {
            JsonNode data = context.get(ref.nodeId()).data();
            if (data == null || !data.has("unit")) continue;
            if (ref.path().equals(".data.observation_seconds") || ref.path().equals(".data.period_seconds")) units.add("second");
            else if (ref.path().equals(".data.percent")) units.add("percent");
            else if (ref.path().equals(".data.value") || ref.path().equals(".data.samples")
                    || ref.path().matches("\\.data\\.samples\\.[0-9]+\\.value")) units.add(data.get("unit").asText());
        }
        return units;
    }
}
