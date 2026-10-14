package com.cexpilot.dag;

import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.metric.*;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** 原文依据、所选任务版本和代码默认值分开处理；执行结果从来不自动升级为查询要求。 */
final class ConditionProvenanceResolver {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("exchange", "metric", "query_shape", "market_type",
            "base", "quote", "settle", "time", "interval", "include_unclosed", "count", "depth");

    CompiledPlan resolve(MetricPlanCompiler compiler, JsonNode planJson, JsonNode explicit,
                         String question, QueryFrame base, RequestContext context, int maxNodes) {
        JsonNode copy = planJson.deepCopy();
        var claims = validateClaims(copy, explicit, question);
        CompiledPlan initial = compiler.compileFull(copy, maxNodes);
        claims.forEach((group, fields) -> fields.keySet().forEach(field -> {
            if (initial.targets().stream().filter(t -> group.equals(t.groupId()))
                    .noneMatch(t -> t.conditionSources().containsKey(field))) {
                throw new IllegalArgumentException("explicit_conditions." + group + "." + field
                        + " 不适用于该指标查询形态，不能标记没有使用的条件");
            }
        }));
        List<EffectiveTarget> matches = InheritanceValidator.matchTargets(
                base == null ? List.of() : base.targets(), initial.targets());
        // 先纠正可选参数：固定要求沿用；上轮自动结果被抄回计划时恢复自动策略。
        // 同组的多所分支有不同继承要求时，不选第一条，要求 Planner 拆组。
        Map<String, Map<String, JsonNode>> edits = new LinkedHashMap<>();
        for (int i = 0; i < initial.targets().size(); i++) {
            EffectiveTarget target = initial.targets().get(i), previous = matches.get(i);
            if (previous == null) continue;
            for (String field : List.of("interval", "include_unclosed", "depth", "settle")) {
                if (!target.conditionSources().containsKey(field)) continue;
                if (!"settle".equals(field) && !target.queryShape().equals(previous.queryShape())) continue;
                JsonNode value = value(target, field), oldValue = value(previous, field);
                JsonNode replacement = value;
                boolean userSpecified = claims.getOrDefault(target.groupId(), Map.of()).containsKey(field);
                if (!userSpecified) {
                    if ("interval".equals(field) && previous.interval() == null && target.interval() != null
                            && previous.executionMetadata() != null
                            && target.interval().equals(previous.executionMetadata().path("candle_interval").asText())) {
                        // 用户可能在本轮要求固定粒度时，不因缺少原文元数据吞掉有效计划；保留为 PLANNED。
                        // 这些词只是阻止自动改写的保守线索，不能据此升级为 EXPLICIT。
                        if (question == null || !question.matches("(?is).*(粒度|颗粒|间隔|保持|固定|自动|每\\s*\\d|\\b\\d+\\s*[mhd]\\b|\\d+\\s*分钟).*")) {
                            replacement = MAPPER.nullNode();
                        }
                    } else if (value.equals(oldValue) && source(previous, field).requirementOrigin() == ConditionOrigin.DEFAULT
                            && !fieldAbsent(copy, target.groupId(), field)) {
                        // 默认 settle/depth/include_unclosed 也不因模型补写就变成固定要求。
                        replacement = MAPPER.nullNode();
                    } else if (fieldAbsent(copy, target.groupId(), field) && !oldValue.isNull()
                            && source(previous, field).requirementOrigin() != ConditionOrigin.DEFAULT) {
                        replacement = oldValue;
                    }
                }
                Map<String, JsonNode> group = edits.computeIfAbsent(target.groupId(), ignored -> new LinkedHashMap<>());
                if (group.containsKey(field) && !group.get(field).equals(replacement)) {
                    throw new IllegalArgumentException("指标组 " + target.groupId() + " 的 " + field
                            + " 在不同交易所继承要求不同，请拆成独立指标组，不能采用第一条的条件");
                }
                group.put(field, replacement);
            }
        }
        for (JsonNode node : copy.path("metrics")) {
            for (var edit : edits.getOrDefault(node.path("id").asText(), Map.of()).entrySet()) {
                // 没有实际修改时保留原省略字段，避免把代码默认又写成模型填写。
                EffectiveTarget first = initial.targets().stream()
                        .filter(t -> t.groupId().equals(node.path("id").asText())).findFirst().orElseThrow();
                if (!value(first, edit.getKey()).equals(edit.getValue())) setField((ObjectNode) node, edit.getKey(), edit.getValue());
            }
        }
        CompiledPlan plan = copy.equals(planJson) ? initial : compiler.compileFull(copy, maxNodes);
        // 使用归一化前相同物理节点的配对；归一化不改变指标组/交易所/节点数量。
        List<EffectiveTarget> targets = new ArrayList<>();
        InheritanceValidator validator = new InheritanceValidator();
        for (int i = 0; i < plan.targets().size(); i++) {
            EffectiveTarget target = plan.targets().get(i), previous = matches.get(i);
            Map<String, ConditionSource> sources = new LinkedHashMap<>(target.conditionSources());
            for (String field : sources.keySet()) {
                String text = claims.getOrDefault(target.groupId(), Map.of()).get(field);
                if (text != null) {
                    sources.put(field, new ConditionSource(ConditionOrigin.EXPLICIT, ConditionOrigin.EXPLICIT,
                            null, null, null, text));
                } else if (previous != null && (Objects.equals(value(previous, field), value(target, field))
                        || ("time".equals(field) && context != null
                            && validator.sameTimeRequirement(base, previous, target, context)))) {
                    sources.put(field, source(previous, field).inherited(base.sourceTurnId(), base.frameId(), base.version()));
                }
            }
            targets.add(target.withMetadata(sources, null));
        }
        return new CompiledPlan(plan.logicalPlan(), plan.logicalPlanJson(), plan.dagPlan(), plan.branches(), targets);
    }

    private static Map<String, Map<String, String>> validateClaims(JsonNode plan, JsonNode claims, String question) {
        if (claims == null || claims.isNull() || claims.isMissingNode()) return Map.of();
        if (!claims.isObject()) throw new IllegalArgumentException("explicit_conditions 必须为指标组 ID 到字段原文的对象");
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        claims.fields().forEachRemaining(group -> {
            JsonNode metric = null;
            for (JsonNode node : plan.path("metrics")) if (group.getKey().equals(node.path("id").asText())) metric = node;
            if (metric == null || !group.getValue().isObject()) {
                throw new IllegalArgumentException("explicit_conditions 指标组不存在或字段原文不是对象: " + group.getKey());
            }
            Map<String, String> fields = new LinkedHashMap<>();
            group.getValue().fields().forEachRemaining(field -> {
                if (!FIELDS.contains(field.getKey()) || !field.getValue().isTextual()
                        || field.getValue().asText().isBlank() || question == null
                        || !question.contains(field.getValue().asText())) {
                    throw new IllegalArgumentException("explicit_conditions." + group.getKey() + "." + field.getKey()
                            + " 必须是当前用户问题中的原文片段，继承/默认条件不能编造原文依据");
                }
                fields.put(field.getKey(), field.getValue().asText());
            });
            result.put(group.getKey(), fields);
        });
        return result;
    }

    /** 旧快照 EXPLICIT 只证明旧模型填了值，不能追认成用户明确要求；DEFAULT 仍可辨识。 */
    private static ConditionSource source(EffectiveTarget target, String field) {
        ConditionSource source = target.conditionSources().get(field);
        if (source != null) return source;
        ConditionOrigin legacy = switch (field) {
            case "interval" -> target.intervalOrigin();
            case "include_unclosed" -> target.includeUnclosedOrigin();
            case "depth" -> target.depthOrigin();
            case "settle" -> target.settleOrigin();
            default -> null;
        };
        return ConditionSource.of(legacy == ConditionOrigin.DEFAULT ? ConditionOrigin.DEFAULT : ConditionOrigin.PLANNED);
    }

    private static boolean fieldAbsent(JsonNode plan, String groupId, String field) {
        for (JsonNode metric : plan.path("metrics")) {
            if (groupId.equals(metric.path("id").asText())) {
                return "settle".equals(field) ? !metric.path("instrument").has(field) : !metric.has(field);
            }
        }
        return true;
    }

    private static void setField(ObjectNode metric, String field, JsonNode value) {
        ObjectNode owner = "settle".equals(field) ? (ObjectNode) metric.path("instrument") : metric;
        if (value.isNull()) owner.remove(field); else owner.set(field, value.deepCopy());
    }

    private static JsonNode value(EffectiveTarget target, String field) {
        return switch (field) {
            case "exchange" -> MAPPER.valueToTree(target.exchange());
            case "metric" -> MAPPER.valueToTree(target.metric());
            case "query_shape" -> MAPPER.valueToTree(target.queryShape());
            case "market_type", "base", "quote", "settle" -> target.instrument().path(field);
            case "time" -> target.timeExpression() == null ? MAPPER.nullNode() : target.timeExpression();
            case "interval" -> MAPPER.valueToTree(target.interval());
            case "include_unclosed" -> MAPPER.valueToTree(target.includeUnclosed());
            case "count" -> MAPPER.valueToTree(target.count());
            case "depth" -> MAPPER.valueToTree(target.depth());
            default -> throw new IllegalArgumentException("未知条件: " + field);
        };
    }
}
