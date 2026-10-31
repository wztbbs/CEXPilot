package com.cexpilot.api;

/**
 * @param conversationId 可选；不传则按 visitorId 复用最近活跃会话（当前约定 1 访客 1 会话），没有则新建；
 *                       传了则带上该对话的最近上下文（多轮追问）
 * @param timezone       可选；IANA 时区（如 Asia/Shanghai），作为本次请求的时间消解上下文，不传由服务端按默认口径处理
 * @param isRealUser     请求来源标记；省略或 null 按 true，仅用于 trace 统计，不影响上下文与执行
 * @param parentTurnId   页面看到的最近完成轮次；不匹配时拒绝执行。空串表示没有历史，null 兼容旧客户端
 */
public record AskRequest(String conversationId, String question, String timezone, String parentTurnId,
                         java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> clarificationAnswers, Boolean isRealUser) {
    public AskRequest {
        isRealUser = !Boolean.FALSE.equals(isRealUser);
    }

    public AskRequest(String conversationId, String question, String timezone, String parentTurnId,
                      java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> clarificationAnswers) {
        this(conversationId, question, timezone, parentTurnId, clarificationAnswers, true);
    }

    public AskRequest(String conversationId, String question, String timezone, String parentTurnId) {
        this(conversationId, question, timezone, parentTurnId, null);
    }
}
