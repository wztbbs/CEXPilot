package com.cexpilot.conversation;

import com.cexpilot.evidence.EvidenceAssembler;

import java.util.List;

/**
 * Planner 输入：分层渲染后的上下文文本 + 活动 QueryFrame 本体 + 可引用历史事实候选。
 * contextText 注入 planner prompt 的 conversation_context 槽位；activeFrame 供程序侧继承对账使用；
 * evidence 为【可引用历史事实】段对应的结构化候选（短别名→事实），供服务端绑定校验，
 * 必须与 contextText 中渲染的别名完全一致。
 * textHistoryCount 为【近期问答】文本历史条数（阶段三 CONTEXT_RESOLVED 观测用）。
 */
public record PlannerInput(String contextText, QueryFrame activeFrame,
                           List<EvidenceAssembler.Entry> evidence, int textHistoryCount) {

    public PlannerInput(String contextText, QueryFrame activeFrame) {
        this(contextText, activeFrame, List.of(), 0);
    }

    public PlannerInput(String contextText, QueryFrame activeFrame,
                        List<EvidenceAssembler.Entry> evidence) {
        this(contextText, activeFrame, evidence, 0);
    }

    public PlannerInput {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
