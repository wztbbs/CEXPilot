package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用本地 fake 数据源驱动真实执行器，检测返回字段/表格列与 YAML 契约漂移。 */
public final class OutputContractAssertions {
    private static final Map<String, JsonNode> SCHEMAS = ToolDefinitionLoader.load(new DefaultResourceLoader()).stream()
            .collect(Collectors.toMap(ToolDefinition::name, ToolDefinition::outputSchema));

    private OutputContractAssertions() {}

    public static void assertKnownPaths(String tool, JsonNode data) {
        check(tool, SCHEMAS.get(tool), data, ".data");
        checkTypes(SCHEMAS.get(tool), data, tool + ".data");
    }

    private static void checkTypes(JsonNode schema, JsonNode data, String path) {
        if (!schema.has("type") || data.isNull()) return; // 条件字段空值仍由运行期处理。
        JsonNode type = schema.get("type");
        String kind = type.isArray() ? type.get(0).asText() : type.asText();
        boolean matches = switch (kind) {
            case "object" -> data.isObject();
            case "array" -> data.isArray();
            case "number" -> data.isNumber();
            case "integer" -> data.isIntegralNumber();
            case "boolean" -> data.isBoolean();
            case "string" -> data.isTextual();
            default -> false;
        };
        assertTrue(matches, () -> path + " 实际类型 " + data.getNodeType() + " 不符合 " + type);
        if (data.isObject()) {
            data.fields().forEachRemaining(e -> checkTypes(schema.path("properties").path(e.getKey()), e.getValue(), path + "." + e.getKey()));
        } else if (data.isArray()) {
            for (int i = 0; i < data.size(); i++) {
                JsonNode child = i < schema.path("prefixItems").size() ? schema.get("prefixItems").get(i) : schema.get("items");
                checkTypes(child, data.get(i), path + "." + i);
            }
        }
    }

    private static void check(String tool, JsonNode schema, JsonNode data, String path) {
        assertNull(ToolOutputSchema.referenceError(schema, path), () -> tool + " 返回了契约未声明的路径 " + path);
        if (data.isObject()) {
            data.fields().forEachRemaining(e -> check(tool, schema, e.getValue(), path + "." + e.getKey()));
        } else if (data.isArray()) {
            for (int i = 0; i < data.size(); i++) check(tool, schema, data.get(i), path + "." + i);
        }
    }
}
