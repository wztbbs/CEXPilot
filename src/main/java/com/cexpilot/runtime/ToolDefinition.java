package com.cexpilot.runtime;

import com.cexpilot.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

/** YAML 是工具元数据的唯一来源；其余 YAML 字段仅作维护文档。 */
public record ToolDefinition(String name, boolean enabled, String description, JsonNode inputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具 name / description 不能为空");
        }
        ToolArguments.validateSchema(inputSchema);
        inputSchema = inputSchema.deepCopy();
    }

    public ToolSpec spec() {
        return new ToolSpec(name, description, inputSchema.deepCopy());
    }
}
