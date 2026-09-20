package com.cexpilot.intent;

import java.util.List;

/**
 * 意图定义。name/description 用于统计归类（planner 的 hint）；
 * evidenceRules（YAML 的 evidence_policy.rules）会注入回答阶段 prompt，
 * 约束该类问题的回答方式。意图不控制工具选择。
 */
public record IntentDefinition(String name, String description, List<String> evidenceRules) {

    public IntentDefinition {
        evidenceRules = evidenceRules == null ? List.of() : List.copyOf(evidenceRules);
    }
}
