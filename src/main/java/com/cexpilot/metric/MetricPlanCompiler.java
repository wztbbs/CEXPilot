package com.cexpilot.metric;

import com.cexpilot.calculation.CalculationTool;
import com.cexpilot.dag.DagPlan;
import com.cexpilot.dag.PlanNode;
import com.cexpilot.runtime.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.*;
import java.util.regex.Pattern;

/** 紧凑逻辑计划 → 现有执行 DAG。没有旧协议回退，也不允许模型选择底层 Tool。 */
public final class MetricPlanCompiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REF = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z0-9_]+)+}}");

    private final MetricCatalog catalog;
    private final ToolRegistry registry;
    private final LogicalPlanParser parser;
    private final MetricQueryResolver resolver;

    public MetricPlanCompiler(MetricCatalog catalog, ToolRegistry registry, MetricProviderRegistry providers) {
        this.catalog = catalog;
        this.registry = registry;
        this.parser = new LogicalPlanParser();
        this.resolver = new MetricQueryResolver(catalog, providers);
    }

    public Set<String> allowedTools() {
        return new HashSet<>(catalog.operators());
    }

    /** JSON 计划 → 可执行 DagPlan；校验与错误反馈保留具体路径。 */
    public DagPlan compile(JsonNode plan, int maxNodes) {
        LogicalPlan logicalPlan = parser.parse(plan);
        validateStructure(logicalPlan, maxNodes);
        List<String> errors = validateLogicalInputs(logicalPlan);

        List<ResolvedMetric> resolvedMetrics = new ArrayList<>();
        int nextId = 0;
        for (int i = 0; i < logicalPlan.metrics().size(); i++) {
            MetricRequest metric = logicalPlan.metrics().get(i);
            try {
                List<ResolvedMetric> resolved = resolver.resolve(metric, nextId);
                resolvedMetrics.addAll(resolved);
                nextId += resolved.size();
            } catch (IllegalArgumentException e) {
                errors.add("metrics[" + i + "] (" + metric.id() + ", " + metric.metric()
                        + ", " + metric.shape().code() + "): " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) fail(String.join("; ", errors));
        if (resolvedMetrics.size() + logicalPlan.calculations().size() > maxNodes) {
            fail("展开后的执行节点超过上限 " + maxNodes);
        }

        Map<String, Map<String, String>> branches = buildBranches(resolvedMetrics);
        Map<String, String> calcIds = logicalPlan.calculations().stream()
                .collect(LinkedHashMap::new, (m, c) -> m.put(c.id(), c.id()), LinkedHashMap::putAll);

        List<PlanNode> nodes = new ArrayList<>();
        for (ResolvedMetric rm : resolvedMetrics) {
            nodes.add(buildMetricNode(rm));
        }
        for (CalculationRequest calc : logicalPlan.calculations()) {
            if (nodes.stream().anyMatch(n -> n.id().equals(calc.id()))) {
                fail("计算 ID 与内部执行节点冲突: " + calc.id());
            }
            nodes.add(buildCalculationNode(calc, branches, calcIds));
        }
        return new DagPlan(nodes);
    }

    private void validateStructure(LogicalPlan logicalPlan, int maxNodes) {
        if ((long) logicalPlan.metrics().size() + logicalPlan.calculations().size() > maxNodes) {
            fail("逻辑计划规模超过上限 " + maxNodes);
        }
    }

    private List<String> validateLogicalInputs(LogicalPlan logicalPlan) {
        List<String> errors = new ArrayList<>();
        Map<String, String> ids = new LinkedHashMap<>();

        for (int i = 0; i < logicalPlan.metrics().size(); i++) {
            MetricRequest metric = logicalPlan.metrics().get(i);
            String previous = ids.putIfAbsent(metric.id(), "metrics[" + i + "].id");
            if (previous != null) {
                errors.add("metrics[" + i + "].id 重复 id: " + metric.id() + "，已用于 " + previous);
            }
        }
        for (int i = 0; i < logicalPlan.calculations().size(); i++) {
            CalculationRequest calc = logicalPlan.calculations().get(i);
            String previous = ids.putIfAbsent(calc.id(), "calculations[" + i + "].id");
            if (previous != null) {
                errors.add("calculations[" + i + "].id 重复 id: " + calc.id() + "，已用于 " + previous);
            }
            if (!catalog.operators().contains(calc.operator()) || !(registry.get(calc.operator()) instanceof CalculationTool tool)) {
                errors.add("calculations[" + i + "] (" + calc.id() + ") 未接入算子: " + calc.operator());
                continue;
            }
            ObjectNode args = MAPPER.createObjectNode();
            args.set("input", calc.input());
            try {
                tool.validateArguments(args, true);
            } catch (IllegalArgumentException e) {
                errors.add("calculations[" + i + "] (" + calc.id() + ", " + calc.operator() + ").input: " + e.getMessage()
                        + "；输入契约: " + tool.planningInputSchema());
            }
        }
        return errors;
    }

    private Map<String, Map<String, String>> buildBranches(List<ResolvedMetric> resolvedMetrics) {
        Map<String, Map<String, String>> branches = new LinkedHashMap<>();
        for (ResolvedMetric rm : resolvedMetrics) {
            branches.computeIfAbsent(rm.request().id(), k -> new LinkedHashMap<>())
                    .put(rm.exchange(), rm.physicalId());
        }
        return branches;
    }

    private PlanNode buildMetricNode(ResolvedMetric rm) {
        MetricQuery query = rm.query();
        MetricBinding binding = query.binding();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("exchange", rm.exchange())
                .put("symbol", binding.instrument().path("base").asText())
                .put("market_type", "perpetual")
                .put("quote_asset", "USDT");
        if (query instanceof TimeRangeQuery q) {
            args.set("time", com.cexpilot.time.TimeSpecJson.write(q.time()));
            if (q.intervalCode() != null) args.put("interval", q.intervalCode());
            if (q.includeUnclosed()) args.put("include_unclosed", true);
        } else if (query instanceof CountQuery q) {
            args.put("count", q.count());
        } else if (query instanceof SnapshotQuery q) {
            if (q.depth() != null) args.put("depth", q.depth());
        }
        return new PlanNode(rm.physicalId(), null, args, List.of(), query);
    }

    private PlanNode buildCalculationNode(CalculationRequest calc,
                                          Map<String, Map<String, String>> branches,
                                          Map<String, String> calcIds) {
        Set<String> deps = new LinkedHashSet<>();
        ObjectNode args = MAPPER.createObjectNode();
        args.set("input", rewrite(calc.input(), branches, calcIds, deps));
        return new PlanNode(calc.id(), calc.operator(), args, List.copyOf(deps));
    }

    private JsonNode rewrite(JsonNode node, Map<String, Map<String, String>> branches,
                             Map<String, String> calculations, Set<String> deps) {
        if (node.isTextual() && (node.asText().contains("{{") || node.asText().contains("}}"))) {
            String value = node.asText();
            if (!REF.matcher(value).matches()) {
                fail("引用必须是完整字段引用，不能包含表达式或插值: " + value);
            }
            String[] parts = value.substring(2, value.length() - 2).split("\\.");
            String target;
            int offset;
            if (branches.containsKey(parts[0])) {
                if (parts.length < 3 || !branches.get(parts[0]).containsKey(parts[1])) {
                    fail("指标引用必须包含该组内的交易所: " + value);
                }
                target = branches.get(parts[0]).get(parts[1]);
                offset = 2;
            } else {
                target = calculations.get(parts[0]);
                if (target == null) fail("引用不存在的指标组或计算节点: " + value);
                offset = 1;
            }
            deps.add(target);
            return TextNode.valueOf("{{" + target + ".data." + String.join(".", Arrays.copyOfRange(parts, offset, parts.length)) + "}}");
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), rewrite(e.getValue(), branches, calculations, deps)));
            return copy;
        }
        if (node.isArray()) {
            var copy = MAPPER.createArrayNode();
            node.forEach(child -> copy.add(rewrite(child, branches, calculations, deps)));
            return copy;
        }
        return node.deepCopy();
    }

    /** 修复反馈也使用逻辑引用，避免要求模型理解编译后的 ID 和 Tool 字段。 */
    public List<String> logicalErrors(DagPlan plan, List<String> errors) {
        return errors.stream().map(error -> {
            String message = error;
            for (PlanNode node : plan.nodes()) {
                if (node.metric() != null) {
                    String logical = node.metric().groupId() + "." + node.metric().exchange();
                    message = message.replace("{{" + node.id() + ".data.", "{{" + logical + ".")
                            .replace(node.id() + " ", logical + " ");
                } else {
                    message = message.replace("{{" + node.id() + ".data.", "{{" + node.id() + ".");
                }
            }
            return message;
        }).toList();
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
