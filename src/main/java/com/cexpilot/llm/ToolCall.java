package com.cexpilot.llm;

/**
 * LLM 返回的一次工具调用请求。
 * argumentsJson 是模型生成的 JSON 字符串，由 runtime 解析后交给 Tool。
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
