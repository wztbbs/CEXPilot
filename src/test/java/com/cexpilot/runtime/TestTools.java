package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** DAG 单元测试的合成工具；生产元数据始终通过 YAML 加载。 */
public final class TestTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public interface TestTool extends AgentTool {
        String description();
        JsonNode inputSchema();
    }

    public static ToolRegistry registry(List<? extends AgentTool> tools) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (AgentTool executor : tools) {
            TestTool tool = (TestTool) executor;
            ObjectNode schema = tool.inputSchema().deepCopy();
            schema.put("type", "object");
            if (!schema.has("properties")) {
                ObjectNode properties = schema.putObject("properties");
                for (JsonNode required : schema.path("required")) {
                    properties.putObject(required.asText()).put("type", "string");
                }
            }
            definitions.add(new ToolDefinition(tool.name(), true, tool.description(), schema));
        }
        return new ToolRegistry(new ArrayList<>(tools), definitions);
    }
}
