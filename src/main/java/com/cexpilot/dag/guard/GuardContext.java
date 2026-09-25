package com.cexpilot.dag.guard;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 仅承接整个任务的能力要求。时间、样本数量和市场范围只在各节点 args 中表达，
 * 不再维护一份查询参数或按工具名判断时间能力。
 */
public record GuardContext(boolean valid, boolean requiresPeriodComparison) {
    public static GuardContext of(JsonNode requirements) {
        boolean valid = requirements != null && requirements.isObject()
                && requirements.size() == 1
                && requirements.path("requires_period_comparison").isBoolean();
        return new GuardContext(valid,
                valid && requirements.path("requires_period_comparison").booleanValue());
    }
}
