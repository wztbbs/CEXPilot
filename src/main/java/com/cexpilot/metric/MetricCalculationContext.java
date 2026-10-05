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
    private MetricCalculationContext() {}

    public static void validate(PlanNode node, DagContext context) {
        Set<String> units = units(node, context);
        if (SAME_UNIT.contains(node.tool()) && units.size() > 1) {
            throw new IllegalArgumentException("计算输入单位不一致: " + units);
        }
        if ("annualize".equals(node.tool())) {
            JsonNode rate = node.args().path("input").path("rate");
            for (ReferenceResolver.Ref ref : ReferenceResolver.findRefs(rate)) {
                JsonNode data = context.get(ref.nodeId()).data();
                if (data != null && data.has("metric") && (!"price.change_pct".equals(data.path("metric").asText())
                        || !".data.value".equals(ref.path())
                        || !"holding_return".equals(node.args().path("input").path("basis").asText())
                        || !"percent".equals(node.args().path("input").path("rate_unit").asText()))) {
                    throw new IllegalArgumentException("K 线年化需引用 price.change_pct.value，basis=holding_return，rate_unit=percent");
                }
            }
        }
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
            if (ref.path().equals(".data.observation_seconds")) units.add("second");
            else if (ref.path().equals(".data.percent")) units.add("percent");
            else if (ref.path().equals(".data.value") || ref.path().equals(".data.samples")
                    || ref.path().matches("\\.data\\.samples\\.[0-9]+\\.value")) units.add(data.get("unit").asText());
        }
        return units;
    }
}
