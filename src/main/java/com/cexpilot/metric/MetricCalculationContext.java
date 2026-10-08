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
     * 且周期必须引用同一结果的 period_seconds，不接受默认 8 小时。
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
        List<ReferenceResolver.Ref> periodRefs = ReferenceResolver.findRefs(input.path("period"));
        for (ReferenceResolver.Ref ref : ReferenceResolver.findRefs(input.path("rate"))) {
            JsonNode data = context.get(ref.nodeId()).data();
            if (data == null || !data.has("metric")) continue;
            AnnualizeInput rule = annualizeRule(data.path("metric").asText(), ref.path(), input);
            if (rule == null) {
                throw new IllegalArgumentException("年化只支持：price.change_pct 的 value（basis=holding_return，rate_unit=percent）"
                        + "，或 funding.rate_settled 单期的 samples.0.value（count=1，basis=periodic_rate，rate_unit=ratio）");
            }
            if (!rule.singlePeriod()) continue;
            if (data.path("requested_count").asInt() != 1 || !data.path("sample_complete").asBoolean(true)) {
                throw new IllegalArgumentException("单期费率年化只能引用 count=1 的取样结果：samples 按结算时间升序，"
                        + "取多期时 samples.0 是最旧一期，不是最近一期");
            }
            // 结算周期因合约而异，本次取数已给出 period_seconds；不接受"默认 8 小时"之类的常量。
            if (periodRefs.isEmpty()) {
                throw new IllegalArgumentException("单期费率年化的 period 必须引用同一结果的 period_seconds，"
                        + "不能用默认周期常量外推");
            }
            requireSameSourcePeriod(ref, periodRefs);
        }
    }

    /** 周期要么由用户明确给出常量，要么引用同一结果的 period_seconds；不允许用默认周期外推。 */
    private static void requireSameSourcePeriod(ReferenceResolver.Ref rateRef, List<ReferenceResolver.Ref> periodRefs) {
        for (ReferenceResolver.Ref periodRef : periodRefs) {
            if (!periodRef.nodeId().equals(rateRef.nodeId()) || !".data.period_seconds".equals(periodRef.path())) {
                throw new IllegalArgumentException("单期费率年化的 period 必须引用同一结果的 period_seconds，"
                        + "不接受默认 8 小时或跨结果的周期");
            }
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
                        "catalog_version", "estimated", "truncated", "source")) {
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
