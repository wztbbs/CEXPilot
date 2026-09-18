package com.cexpilot.dag;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Plan 中的一个节点：一次工具调用。
 * args 的字符串值可包含 {{nodeId.path}} 引用上游节点输出，运行期由 ReferenceResolver 解析。
 */
public record PlanNode(String id,
                       String tool,
                       JsonNode args,
                       List<String> dependsOn) {

    public PlanNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
