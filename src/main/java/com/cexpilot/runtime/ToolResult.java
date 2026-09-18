package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolResult(boolean ok, JsonNode data, String error) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResult success(JsonNode data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }

    /** 序列化成发回给 LLM 的 tool message 内容。 */
    public String toMessageContent() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", ok);
        if (data != null) {
            node.set("data", data);
        }
        if (error != null) {
            node.put("error", error);
        }
        return node.toString();
    }
}
