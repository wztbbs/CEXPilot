package com.cexpilot.runtime;

import com.cexpilot.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

/** YAML 是工具描述、输入参数和输出字段契约的唯一来源。 */
public record ToolDefinition(String name, boolean enabled, String description, JsonNode inputSchema,
                             JsonNode outputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具 name / description 不能为空");
        }
        ToolArguments.validateSchema(inputSchema);
        inputSchema = inputSchema.deepCopy();
        ToolOutputSchema.validateSchema(outputSchema);
        outputSchema = outputSchema.deepCopy();
    }

    public ToolSpec spec() {
        return new ToolSpec(name, description, inputSchema.deepCopy());
    }
}
