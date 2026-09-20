package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 所有可被 LLM 调用的工具都实现它。
 * 工具内部是确定性代码：取数、标准化、计算都在 execute 里完成，
 * 返回给 LLM 的是 Structured Facts，不是交易所原始 JSON。
 */
public interface AgentTool {

    String name();

    ToolResult execute(JsonNode args, ToolContext ctx);
}
