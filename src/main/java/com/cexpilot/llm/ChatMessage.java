package com.cexpilot.llm;

import java.util.List;

/**
 * OpenAI 兼容协议的一条消息。
 * role: system / user / assistant / tool
 * assistant 消息可携带 toolCalls；tool 消息必须带 toolCallId 与 name。
 */
public record ChatMessage(String role,
                          String content,
                          List<ToolCall> toolCalls,
                          String toolCallId,
                          String name) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null, null);
    }

    public static ChatMessage tool(String toolCallId, String name, String content) {
        return new ChatMessage("tool", content, null, toolCallId, name);
    }
}
