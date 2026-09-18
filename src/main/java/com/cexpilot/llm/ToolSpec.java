package com.cexpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 暴露给 LLM 的工具描述：名字 + 自然语言说明 + JSON Schema 入参。
 */
public record ToolSpec(String name, String description, JsonNode inputSchema) {
}
