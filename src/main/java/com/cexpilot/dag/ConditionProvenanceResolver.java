package com.cexpilot.dag;

import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.metric.*;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.function.Consumer;

/** 计划与来源分开校验；新协议只声明影响继承的可选参数操作，兼容旧原文来源。 */
final class ConditionProvenanceResolver {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> OPTIONAL = List.of("interval", "include_unclosed", "depth", "settle");
    private static final Set<String> FIELDS = Set.of("exchange", "metric", "query_shape", "market_type",
            "base", "quote", "settle", "time", "interval", "include_unclosed", "count", "depth");
    record Warning(String code, String path, String message) {}
    private record Claims(Map<String, Map<String, String>> valid, Map<String, Set<String>> unverified) {}

    CompiledPlan resolve(MetricPlanCompiler compiler, JsonNode planJson, JsonNode explicit,
                         String question, QueryFrame base, RequestContext context, int maxNodes) {
        return resolve(compiler, planJson, explicit, null, question, base, context, maxNodes, ignored -> {});
    }

    CompiledPlan resolve(MetricPlanCompiler compiler, JsonNode planJson, JsonNode explicit, JsonNode updates,
                         String question, QueryFrame base, RequestContext context, int maxNodes,
                         Consumer<Warning> warnings) {
        JsonNode copy = planJson.deepCopy();
        List<String> errors = new ArrayList<>();
        var actions = readActions(copy, updates, errors);
        CompiledPlan initial;
        try { initial = compiler.compileFull(copy, maxNodes); }
        catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        actions.forEach((group, fields) -> fields.forEach((field, action) -> {
            if (initial.targets().stream().filter(t -> group.equals(t.groupId()))
                    .anyMatch(t -> !compiler.supportsCondition(t, field)))
                errors.add("condition_updates." + group + "." + field + " 不适用于该指标查询形态");
        }));
        var claims = readClaims(initial, explicit, question, compiler, warnings);
        List<EffectiveTarget> matches = InheritanceValidator.matchTargets(
                base == null ? List.of() : base.targets(), initial.targets());
        Map<String, Map<String, JsonNode>> edits = new LinkedHashMap<>();
        for (int i = 0; i < initial.targets().size(); i++) {
            EffectiveTarget target = initial.targets().get(i), previous = matches.get(i);
            if (previous == null) continue;
            for (String field : OPTIONAL) {
                if (!compiler.supportsCondition(target, field)) continue;
                if (!"settle".equals(field) && !target.queryShape().equals(previous.queryShape())) continue;
                JsonNode current = value(target, field);
                boolean userSpecified = actions.getOrDefault(target.groupId(), Map.of()).containsKey(field)
                        || claims.valid().getOrDefault(target.groupId(), Map.of()).containsKey(field);
                JsonNode replacement = userSpecified ? current : inheritedValue(copy, target, previous, question, field);
                if (!userSpecified && !replacement.equals(current)
                        && claims.unverified().getOrDefault(target.groupId(), Set.of()).contains(field)) {
                    errors.add("explicit_conditions." + target.groupId() + "." + field
                            + " 原文依据无效，忽略会影响继承；请用 condition_updates 声明 set/reset，"
                            + "set 时在计划提供值，reset 时省略值，不编造原文");
                }
                Map<String, JsonNode> group = edits.computeIfAbsent(target.groupId(), ignored -> new LinkedHashMap<>());
                if (group.containsKey(field) && !group.get(field).equals(replacement)) {
                    errors.add("指标组 " + target.groupId() + " 的 " + field
                            + " 在不同交易所继承要求不同，请拆成独立指标组，不能采用第一条的条件");
                } else group.put(field, replacement);
            }
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", new LinkedHashSet<>(errors)));
        for (JsonNode node : copy.path("metrics")) {
            for (var edit : edits.getOrDefault(node.path("id").asText(), Map.of()).entrySet()) {
                EffectiveTarget first = initial.targets().stream()
                        .filter(t -> t.groupId().equals(node.path("id").asText())).findFirst().orElseThrow();
                if (!value(first, edit.getKey()).equals(edit.getValue())) setField((ObjectNode) node, edit.getKey(), edit.getValue());
            }
        }
        CompiledPlan plan = copy.equals(planJson) ? initial : compiler.compileFull(copy, maxNodes);
        List<EffectiveTarget> targets = new ArrayList<>();
        InheritanceValidator validator = new InheritanceValidator();
        for (int i = 0; i < plan.targets().size(); i++) {
            EffectiveTarget target = plan.targets().get(i), previous = matches.get(i);
            Map<String, ConditionSource> sources = new LinkedHashMap<>(target.conditionSources());
            for (String field : sources.keySet()) {
                String action = actions.getOrDefault(target.groupId(), Map.of()).get(field);
                String text = claims.valid().getOrDefault(target.groupId(), Map.of()).get(field);
                if (action != null) {
                    // reset 是本轮明确操作，但要求已恢复 DEFAULT，之后不能被当作固定参数继承。
                    sources.put(field, new ConditionSource(ConditionOrigin.EXPLICIT,
                            "reset".equals(action) ? ConditionOrigin.DEFAULT : ConditionOrigin.EXPLICIT,
                            null, null, null, null));
                } else if (text != null) {
                    sources.put(field, new ConditionSource(ConditionOrigin.EXPLICIT, ConditionOrigin.EXPLICIT,
                            null, null, null, text));
                } else if (claims.unverified().getOrDefault(target.groupId(), Set.of()).contains(field)) {
                    // 未核实标注不得冒充本轮 EXPLICIT，也不把旧原文拼接成当前依据。
                    if (!fieldAbsent(copy, target.groupId(), field)) sources.put(field, ConditionSource.of(ConditionOrigin.PLANNED));
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

    private static JsonNode inheritedValue(JsonNode copy, EffectiveTarget target, EffectiveTarget previous,
                                            String question, String field) {
        JsonNode current = value(target, field), old = value(previous, field);
        if ("interval".equals(field) && previous.interval() == null && target.interval() != null
                && previous.executionMetadata() != null
                && target.interval().equals(previous.executionMetadata().path("candle_interval").asText())) {
            // 仅是阻止自动改写的保守线索，不能据此升级为 EXPLICIT。
            if (question == null || !question.matches("(?is).*(粒度|颗粒|间隔|保持|固定|自动|每\\s*\\d|\\b\\d+\\s*[mhd]\\b|\\d+\\s*分钟).*"))
                return MAPPER.nullNode();
        } else if (current.equals(old) && source(previous, field).requirementOrigin() == ConditionOrigin.DEFAULT
                && !fieldAbsent(copy, target.groupId(), field)) {
            return MAPPER.nullNode();
        } else if (fieldAbsent(copy, target.groupId(), field) && !old.isNull()
                && source(previous, field).requirementOrigin() != ConditionOrigin.DEFAULT) {
            return old;
        }
        return current;
    }

    private static Map<String, Map<String, String>> readActions(JsonNode plan, JsonNode updates, List<String> errors) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (updates == null || updates.isNull() || updates.isMissingNode()) return result;
        if (!updates.isObject()) {
            errors.add("condition_updates 必须为指标组 ID 到可选参数 set/reset 的对象");
            return result;
        }
        updates.fields().forEachRemaining(group -> {
            ObjectNode metric = metric(plan, group.getKey());
            if (metric == null || !group.getValue().isObject()) {
                errors.add("condition_updates 指标组不存在或操作不是对象: " + group.getKey());
                return;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            group.getValue().fields().forEachRemaining(field -> {
                String path = "condition_updates." + group.getKey() + "." + field.getKey();
                if (!OPTIONAL.contains(field.getKey()) || !field.getValue().isTextual()
                        || !Set.of("set", "reset").contains(field.getValue().asText())) {
                    errors.add(path + " 仅支持 interval/include_unclosed/depth/settle 的 set/reset 操作");
                    return;
                }
                JsonNode raw = rawValue(metric, field.getKey());
                if ("set".equals(field.getValue().asText()) && (raw.isMissingNode() || raw.isNull())) {
                    errors.add(path + "=set 时必须在计划中显式提供该参数的值");
                } else if ("reset".equals(field.getValue().asText()) && !raw.isMissingNode() && !raw.isNull()) {
                    errors.add(path + "=reset 时必须省略计划中的该参数，不能同时指定固定值");
                } else {
                    fields.put(field.getKey(), field.getValue().asText());
                    if ("reset".equals(field.getValue().asText())) setField(metric, field.getKey(), MAPPER.nullNode());
                }
            });
            result.put(group.getKey(), fields);
        });
        return result;
    }

    private static Claims readClaims(CompiledPlan plan, JsonNode explicit, String question,
                                      MetricPlanCompiler compiler, Consumer<Warning> warnings) {
        Map<String, Map<String, String>> valid = new LinkedHashMap<>();
        Map<String, Set<String>> unverified = new LinkedHashMap<>();
        if (explicit == null || explicit.isNull() || explicit.isMissingNode()) return new Claims(valid, unverified);
        if (!explicit.isObject()) {
            warnings.accept(new Warning("LEGACY_FORMAT_IGNORED", "explicit_conditions", "旧来源标注不是对象，已忽略"));
            return new Claims(valid, unverified);
        }
        explicit.fields().forEachRemaining(group -> {
            String prefix = "explicit_conditions." + group.getKey();
            var targets = plan.targets().stream().filter(t -> t.groupId().equals(group.getKey())).toList();
            if (targets.isEmpty() || !group.getValue().isObject()) {
                warnings.accept(new Warning("LEGACY_GROUP_IGNORED", prefix, "指标组不存在或来源标注不是对象，已忽略"));
                return;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            group.getValue().fields().forEachRemaining(field -> {
                String path = prefix + "." + field.getKey();
                if (!FIELDS.contains(field.getKey()) || targets.stream().anyMatch(t -> !compiler.supportsCondition(t, field.getKey()))) {
                    warnings.accept(new Warning("LEGACY_FIELD_IGNORED", path, "字段未知或不适用于该指标查询形态，已忽略"));
                } else if (!field.getValue().isTextual() || field.getValue().asText().isBlank()
                        || question == null || !question.contains(field.getValue().asText())) {
                    warnings.accept(new Warning("LEGACY_SOURCE_UNVERIFIED", path, "原文依据无效，来源降级为未核实"));
                    unverified.computeIfAbsent(group.getKey(), ignored -> new LinkedHashSet<>()).add(field.getKey());
                } else fields.put(field.getKey(), field.getValue().asText());
            });
            valid.put(group.getKey(), fields);
        });
        return new Claims(valid, unverified);
    }

    private static ObjectNode metric(JsonNode plan, String group) {
        for (JsonNode node : plan.path("metrics"))
            if (node.isObject() && group.equals(node.path("id").asText())) return (ObjectNode) node;
        return null;
    }

    private static JsonNode rawValue(ObjectNode metric, String field) {
        return "settle".equals(field) ? metric.path("instrument").path(field) : metric.path(field);
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
        if ("settle".equals(field) && !(metric.path("instrument") instanceof ObjectNode)) return;
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
