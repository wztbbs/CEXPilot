package com.cexpilot.evidence;

import java.util.List;

/**
 * Planner 侧的历史事实绑定上下文（阶段二）：会话 id（绑定校验的会话范围）、
 * 本轮候选（短别名→事实，EvidenceAssembler 分配）、事实库存取。
 * 候选列表必须与注入 prompt 的【可引用历史事实】段完全一致，别名才指向同一事实。
 */
public record EvidenceBindingContext(String conversationId,
                                     List<EvidenceAssembler.Entry> candidates,
                                     EvidenceStore store) {
    public EvidenceBindingContext {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
