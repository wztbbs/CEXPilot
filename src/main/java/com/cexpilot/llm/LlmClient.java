package com.cexpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Consumer;

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

    /**
     * 流式回答：onDelta 逐段接收增量文本，返回聚合后的完整响应（含 token 用量）。
     * 默认实现退化为非流式：拿到完整响应后一次性回调，不支持流式的实现也能接入。
     */
    default ChatResponse chatStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                    Consumer<String> onDelta) {
        ChatResponse response = chat(messages, tools);
        if (response.content() != null && !response.content().isEmpty()) {
            onDelta.accept(response.content());
        }
        return response;
    }
}
