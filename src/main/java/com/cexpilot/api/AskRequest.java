package com.cexpilot.api;

/**
 * @param conversationId 可选；不传则按 visitorId 复用最近活跃会话（当前约定 1 访客 1 会话），没有则新建；
 *                       传了则带上该对话的最近上下文（多轮追问）
 * @param timezone       可选；IANA 时区（如 Asia/Shanghai），作为本次请求的时间消解上下文，不传由服务端按默认口径处理
 * @param parentTurnId   可选；前端声明的上一轮 turnId（用于后续检测多标签页陈旧上下文），不传不影响流程
 */
public record AskRequest(String conversationId, String question, String timezone, String parentTurnId) {
}
