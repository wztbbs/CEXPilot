package com.cexpilot.llm;

import java.util.List;

public interface LlmClient {

    /**
     * @param tools 允许为 null 或空，表示本轮不提供工具（强制模型直接回答）。
     */
    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);
}
