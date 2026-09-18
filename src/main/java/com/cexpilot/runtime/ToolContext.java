package com.cexpilot.runtime;

/**
 * 一次工具调用的上下文：trace 归属 + 对话归属。
 */
public record ToolContext(String traceId, String conversationId) {
}
