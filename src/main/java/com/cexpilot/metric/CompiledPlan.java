package com.cexpilot.metric;

import com.cexpilot.dag.DagPlan;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译产物：校验通过的 LogicalPlan（类型化 + 计划原文）、展开后的 DagPlan、
 * 逻辑指标节点→交易所→物理节点映射、每个指标节点补齐后的有效条件（含来源）。
 * 拿到 DagPlan 后不再通过节点命名反猜逻辑查询。
 */
public record CompiledPlan(
        LogicalPlan logicalPlan,
        JsonNode logicalPlanJson,
        DagPlan dagPlan,
        Map<String, Map<String, String>> branches,
        List<EffectiveTarget> targets) {

    public CompiledPlan {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        branches.forEach((k, v) -> copy.put(k, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(v))));
        branches = java.util.Collections.unmodifiableMap(copy);
        targets = List.copyOf(targets);
    }
}
