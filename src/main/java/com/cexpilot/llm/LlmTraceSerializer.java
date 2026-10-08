package com.cexpilot.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把 LLM 调用侧的实际消息/响应序列化为 JSON，供 trace_event.input_json / output_json 落库。
 * 与业务 ObjectMapper 隔离，避免 null 字段污染审计内容。
 */
public final class LlmTraceSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private LlmTraceSerializer() {
    }

    public static String messagesToJson(List<ChatMessage> messages) {
        return messagesToArray(messages).toString();
    }

    public static ArrayNode messagesToArray(List<ChatMessage> messages) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ChatMessage message : messages) {
            array.add(messageNode(message));
        }
        return array;
    }

    public static String responseToJson(ChatResponse response) {
        return responseToObject(response).toString();
    }

    public static ObjectNode responseToObject(ChatResponse response) {
        ObjectNode node = MAPPER.createObjectNode();
        if (response.content() != null) {
            node.put("content", response.content());
        }
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            node.set("tool_calls", toolCallsNode(response.toolCalls()));
        }
        return node;
    }

    public static JsonNode messageNode(ChatMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", message.role());
        if (message.content() != null) {
            node.put("content", message.content());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            node.set("tool_calls", toolCallsNode(message.toolCalls()));
        }
        if (message.toolCallId() != null) {
            node.put("tool_call_id", message.toolCallId());
        }
        if (message.name() != null) {
            node.put("name", message.name());
        }
        return node;
    }

    public static ArrayNode toolCallsNode(List<ToolCall> toolCalls) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ToolCall toolCall : toolCalls) {
            ObjectNode node = MAPPER.createObjectNode();
            if (toolCall.id() != null) {
                node.put("id", toolCall.id());
            }
            if (toolCall.name() != null) {
                node.put("name", toolCall.name());
            }
            if (toolCall.argumentsJson() != null) {
                node.put("arguments", toolCall.argumentsJson());
            }
            array.add(node);
        }
        return array;
    }
}
