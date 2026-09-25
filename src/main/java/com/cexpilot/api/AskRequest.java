package com.cexpilot.api;

/**
 * @param conversationId 可选；不传则创建新对话，传了则带上该对话的最近上下文（多轮追问）
 * @param timezone       可选；IANA 时区（如 Asia/Shanghai），作为本次请求的时间消解上下文，不传由服务端按默认口径处理
 */
public record AskRequest(String conversationId, String question, String timezone) {
}
