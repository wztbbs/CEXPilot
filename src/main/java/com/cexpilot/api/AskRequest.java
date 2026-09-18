package com.cexpilot.api;

/**
 * @param conversationId 可选；不传则创建新对话，传了则带上该对话的最近上下文（多轮追问）
 */
public record AskRequest(String conversationId, String question) {
}
