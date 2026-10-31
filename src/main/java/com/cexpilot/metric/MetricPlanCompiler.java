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

    /** 来源/继承操作也按实际目录约束判断，不能只因目标上有占位来源就认定参数适用。 */
    public boolean supportsCondition(EffectiveTarget target, String field) {
        var binding = catalog.metricDefinition(target.metric())
                .bindings(target.instrument().path("market_type").asText())
                .get(QueryShape.from(target.queryShape()));
        return switch (field) {
            case "interval", "include_unclosed" -> binding != null && binding.hasIntervalSupport();
            case "depth" -> binding != null && binding.depth() != null;
            case "settle" -> "perpetual".equals(target.instrument().path("market_type").asText());
            default -> target.conditionSources().containsKey(field);
        };
    }

    /** JSON 计划 → 可执行 DagPlan；校验与错误反馈保留具体路径。 */
    public DagPlan compile(JsonNode plan, int maxNodes) {
        return compileFull(plan, maxNodes).dagPlan();
    }

    /** JSON 计划 → 编译产物（类型化 LogicalPlan + 可执行 DagPlan + 映射 + 有效条件）；校验与错误反馈保留具体路径。 */
    public CompiledPlan compileFull(JsonNode plan, int maxNodes) {
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
        List<EffectiveTarget> targets = new ArrayList<>();
        for (ResolvedMetric rm : resolvedMetrics) {
            targets.add(effectiveTarget(rm));
        }
        return new CompiledPlan(logicalPlan, plan.deepCopy(), new DagPlan(nodes), branches, targets);
    }

    /**
     * 每个展开的指标节点的有效查询条件及来源：Planner 填写的 → PLANNED（尚无用户原文依据）；
     * 代码自动补的（自动粒度、默认 settle、include_unclosed 默认、默认 depth、目录推导 unit/provider）→ DEFAULT。
     * EXPLICIT/INHERITED 由规划上下文对账产生，实际范围/粒度在 QueryFrame 提交时回填。
     */
    private EffectiveTarget effectiveTarget(ResolvedMetric rm) {
        MetricRequest request = rm.request();
        MetricBinding binding = rm.query().binding();
        QueryParams params = request.params();
        JsonNode timeExpression = null;
        String interval = null;
        ConditionOrigin intervalOrigin = null;
        Boolean includeUnclosed = null;
        ConditionOrigin includeUnclosedOrigin = null;
        Integer count = null;
        Integer depth = null;
        ConditionOrigin depthOrigin = null;
        if (params instanceof TimeQueryParams p) {
            timeExpression = com.cexpilot.time.TimeSpecJson.write(p.time());
            interval = p.interval();
            intervalOrigin = interval != null ? ConditionOrigin.PLANNED : ConditionOrigin.DEFAULT;
            includeUnclosed = p.includeUnclosed() != null && p.includeUnclosed();
            includeUnclosedOrigin = p.includeUnclosed() != null ? ConditionOrigin.PLANNED : ConditionOrigin.DEFAULT;
        } else if (params instanceof CountQueryParams p) {
            count = p.count();
        } else if (params instanceof SnapshotQueryParams p) {
            depth = p.depth() != null ? p.depth()
                    : (rm.query() instanceof SnapshotQuery q ? q.depth() : null);
            depthOrigin = p.depth() != null ? ConditionOrigin.PLANNED : ConditionOrigin.DEFAULT;
        }
        EffectiveTarget target = new EffectiveTarget(
                request.id(), rm.physicalId(), rm.exchange(), ConditionOrigin.PLANNED,
                request.metric(), request.shape().code(),
                binding.instrument().deepCopy(),
                request.instrument().settle() != null ? ConditionOrigin.PLANNED : ConditionOrigin.DEFAULT,
                timeExpression,
                interval, intervalOrigin,
                includeUnclosed, includeUnclosedOrigin,
                count, depth, depthOrigin,
                binding.unit(), binding.provider(), binding.catalogVersion());
        Map<String, ConditionSource> sources = new LinkedHashMap<>();
        for (String field : List.of("exchange", "metric", "query_shape", "market_type", "base", "quote")) {
            sources.put(field, ConditionSource.of(ConditionOrigin.PLANNED));
        }
        sources.put("settle", ConditionSource.of(target.settleOrigin()));
        if (timeExpression != null) sources.put("time", ConditionSource.of(ConditionOrigin.PLANNED));
        if (intervalOrigin != null) sources.put("interval", ConditionSource.of(intervalOrigin));
        if (includeUnclosedOrigin != null) sources.put("include_unclosed", ConditionSource.of(includeUnclosedOrigin));
        if (count != null) sources.put("count", ConditionSource.of(ConditionOrigin.PLANNED));
        if (depthOrigin != null) sources.put("depth", ConditionSource.of(depthOrigin));
        return target.withMetadata(sources, null);
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
            checkReferenceKindMismatch(calc, i, errors);
        }
        return errors;
    }

    /**
     * 引用/形态契约错配检查（算子 schema 之外的跨字段语义，审查批次外的规划质量加固）：
     * values 里塞 ".samples" 结尾的序列引用、field 的 collection 指 ".value" 结尾的标量——
     * 两者都能通过算子 schema 校验、执行期才炸（deepseek-v4.1-flash 实测排错后 Answer 只能
     * 说"无法给出均值"），这里静态打回并给出正确形式，让模型在修复循环里一次学会。
     */
    private static void checkReferenceKindMismatch(CalculationRequest calc, int index, List<String> errors) {
        if ("values".equals(calc.input().path("kind").asText())) {
            for (JsonNode v : calc.input().path("values")) {
                if (v.isTextual() && v.asText().matches("\\{\\{.*\\.samples}}")) {
                    errors.add("calculations[" + index + "] (" + calc.id() + ", " + calc.operator()
                            + ").input.values 里放了序列引用 " + v.asText()
                            + "：序列聚合必须用 {\"kind\":\"field\",\"collection\":\"" + v.asText()
                            + "\",\"field\":\"value\"}，values 只放数值、数值字符串或标量引用（如 {{组.交易所.value}}）");
                    return;
                }
            }
        }
        if ("field".equals(calc.input().path("kind").asText())) {
            String collection = calc.input().path("collection").asText();
            if (collection.matches("\\{\\{.*\\.value}}")) {
                errors.add("calculations[" + index + "] (" + calc.id() + ", " + calc.operator()
                        + ").input.collection 里放了标量引用 " + collection
                        + "：collection 必须是序列引用（以 .samples 结尾，如 {{组.交易所.samples}}），"
                        + "对标量做聚合请改用 {\"kind\":\"values\",\"values\":[\"" + collection + "\"]}");
            }
        }
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
                .put("market_type", binding.instrument().path("market_type").asText())
                .put("quote_asset", binding.instrument().path("quote").asText());
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
