package com.cexpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface LlmClient {

    /**
     * @param tools 允许为 null 或空，表示本轮不提供工具（强制模型直接回答）。
     */
    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);

    /**
     * @param responseFormat OpenAI 兼容 response_format 结构化输出约束
     *                       （如 {"type":"json_object"} / {"type":"json_schema", ...}），null 表示不下发。
     *                       默认实现忽略该参数（测试桩等无需感知）。
     */
    default ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, JsonNode responseFormat) {
        return chat(messages, tools);
    }
}
