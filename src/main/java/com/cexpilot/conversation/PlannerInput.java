package com.cexpilot.conversation;

import com.cexpilot.evidence.EvidenceAssembler;

import java.util.List;

/**
 * Planner 输入：分层渲染后的上下文文本 + 活动 QueryFrame 本体 + 可引用历史事实候选。
 * contextText 注入 planner prompt 的 conversation_context 槽位；activeFrame 供程序侧继承对账使用；
 * evidence 为【可引用历史事实】段对应的结构化候选（短别名→事实），供服务端绑定校验，
 * 必须与 contextText 中渲染的别名完全一致。
 */
public record PlannerInput(String contextText, QueryFrame activeFrame,
                           List<EvidenceAssembler.Entry> evidence) {

    public PlannerInput(String contextText, QueryFrame activeFrame) {
        this(contextText, activeFrame, List.of());
    }

    public PlannerInput {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
