package com.cexpilot.llm;

import java.util.List;

/**
 * 一次 LLM 调用的结果。content 与 toolCalls 二选一或同时出现（取决于模型）。
 */
public record ChatResponse(String content,
                           List<ToolCall> toolCalls,
                           Integer promptTokens,
                           Integer completionTokens) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
