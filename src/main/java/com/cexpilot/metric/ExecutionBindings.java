package com.cexpilot.metric;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 执行期绑定（服务端生成，模型不可指定 factId）：物理节点 id → 取数方式。
 * 未出现在表中的节点一律 FETCH（走 Provider 实时取数）；
 * REUSE_FACT 节点由执行器从 EvidenceStore 载入 payload 直接播种进 DagContext。
 */
public record ExecutionBindings(Map<String, ReuseFact> reusedByNode) {

    public ExecutionBindings {
        reusedByNode = reusedByNode == null ? Map.of() : Map.copyOf(reusedByNode);
    }

    public static ExecutionBindings empty() {
        return new ExecutionBindings(Map.of());
    }

    /** 该节点的复用绑定；null 表示 FETCH。 */
    public ReuseFact reuseFor(String nodeId) {
        return reusedByNode.get(nodeId);
    }

    public int reusedCount() {
        return reusedByNode.size();
    }

    /**
     * 绑定到一条已验证历史事实。payload 为该事实缓存的完整 MetricResultJson 输出，
     * 播种后以本轮物理节点 id 进入 DagContext，下游引用解析与算子零改动。
     *
     * @param observedAt 原观测时刻渲染值（快照 as_of；序列类为 null，窗口在 payload identity 中）
     */
    public record ReuseFact(String factId, String sourceTurnId, String observedAt,
                            String fetchedAt, JsonNode payload) {
    }
}
