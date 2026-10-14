package com.cexpilot.dag;

import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 历史绑定修复必须保留每个历史输入的条件与计算用途，逻辑节点改名不改变约束。 */
final class PinnedRequirements {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REFERENCE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\.(.+)}}");

    private record Requirement(JsonNode query, List<String> uses) {}

    private final List<Requirement> required = new ArrayList<>();

    boolean isEmpty() {
        return required.isEmpty();
    }

    /** 仅捕获首次绑定失败的需求；后续修复不能重写这些约束。 */
    void capture(CompiledPlan plan) {
        if (required.isEmpty()) {
            required.addAll(requirements(plan));
        }
    }

    boolean preservedBy(CompiledPlan repaired) {
        List<Requirement> remaining = new ArrayList<>(requirements(repaired));
        // 按多重集检查：一个保留的组不能满足两个原历史输入。
        for (Requirement requirement : required) {
            if (!remaining.remove(requirement)) {
                return false;
            }
        }
        return true;
    }

    private static List<Requirement> requirements(CompiledPlan plan) {
        Map<String, List<String>> uses = new HashMap<>();
        var calculations = new HashMap<String, com.cexpilot.metric.CalculationRequest>();
        for (var calculation : plan.logicalPlan().calculations()) {
            calculations.put(calculation.id(), calculation);
        }
        var consumedCalculations = new java.util.HashSet<String>();
        for (var calculation : calculations.values()) {
            collectReferencedCalculations(calculation.input(), calculations, consumedCalculations);
        }
        // 从最终计算结果追踪输入路径，路径含算子/输入槽位，不含模型生成的节点 ID。
        for (var calculation : calculations.values()) {
            if (!consumedCalculations.contains(calculation.id())) {
                collectUses(calculation.input(), calculation.operator() + "/input", calculations,
                        new java.util.HashSet<>(java.util.Set.of(calculation.id())), uses);
            }
        }
        List<Requirement> result = new ArrayList<>();
        for (var metric : plan.logicalPlan().metrics()) {
            if (!"pinned_result".equals(metric.referenceMode())) {
                continue;
            }
            var query = MAPPER.createArrayNode();
            plan.targets().stream().filter(t -> t.groupId().equals(metric.id()))
                    .sorted(Comparator.comparing(EffectiveTarget::exchange))
                    .forEach(t -> {
                        ObjectNode identity = MAPPER.valueToTree(t);
                        identity.remove(List.of("groupId", "physicalId", "exchangeOrigin", "settleOrigin",
                                "intervalOrigin", "includeUnclosedOrigin", "depthOrigin", "conditionSources", "executionMetadata"));
                        query.add(identity);
                    });
            List<String> paths = new ArrayList<>(uses.getOrDefault(metric.id(), List.of()));
            paths.sort(String::compareTo);
            result.add(new Requirement(query, List.copyOf(paths)));
        }
        return result;
    }

    private static void collectReferencedCalculations(JsonNode input,
            Map<String, com.cexpilot.metric.CalculationRequest> calculations, java.util.Set<String> consumed) {
        if (input.isTextual()) {
            var match = REFERENCE.matcher(input.asText());
            if (match.matches() && calculations.containsKey(match.group(1))) {
                consumed.add(match.group(1));
            }
        } else if (input.isContainerNode()) {
            input.forEach(child -> collectReferencedCalculations(child, calculations, consumed));
        }
    }

    private static void collectUses(JsonNode input, String path,
            Map<String, com.cexpilot.metric.CalculationRequest> calculations, java.util.Set<String> visiting,
            Map<String, List<String>> uses) {
        if (input.isTextual()) {
            var match = REFERENCE.matcher(input.asText());
            if (!match.matches()) {
                return;
            }
            String id = match.group(1);
            var calculation = calculations.get(id);
            if (calculation == null) {
                uses.computeIfAbsent(id, ignored -> new ArrayList<>()).add(path + "/" + match.group(2));
            } else if (visiting.add(id)) {
                collectUses(calculation.input(), path + "/" + match.group(2) + "/" + calculation.operator()
                        + "/input", calculations, visiting, uses);
                visiting.remove(id);
            }
        } else if (input.isObject()) {
            input.fields().forEachRemaining(e -> collectUses(e.getValue(), path + "/" + e.getKey(),
                    calculations, visiting, uses));
        } else if (input.isArray()) {
            for (int i = 0; i < input.size(); i++) {
                collectUses(input.get(i), path + "/" + i, calculations, visiting, uses);
            }
        }
    }
}
