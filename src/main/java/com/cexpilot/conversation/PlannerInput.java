package com.cexpilot.conversation;

/**
 * Planner 输入：分层渲染后的上下文文本 + 活动 QueryFrame 本体。
 * contextText 注入 planner prompt 的 conversation_context 槽位；activeFrame 供程序侧继承对账使用。
 */
public record PlannerInput(String contextText, QueryFrame activeFrame) {
}
