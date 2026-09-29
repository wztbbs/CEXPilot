package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 输出字段的结构契约（JSON Schema 子集），同时用于提示词和规划期路径校验。
 * 只证明路径在结构上合法，不保证可选字段本次存在、非 null 或序列有足够的行。
 * 无 type 的节点表示形状取决于运行时输入（如 min.item），其内部路径留给 ReferenceResolver。
 */
public final class ToolOutputSchema {
    private static final Set<String> KEYS = Set.of(
            "type", "description", "properties", "additionalProperties", "items", "prefixItems");
    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "number", "integer", "boolean", "null");

    private ToolOutputSchema() {}

    public static void validateSchema(JsonNode schema) {
        validateSchema(schema, "output_schema");
    }

    private static void validateSchema(JsonNode schema, String path) {
        if (schema == null || !schema.isObject()) fail(path, "必须是 schema 对象");
        schema.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) fail(path, "不支持的关键字 " + key);
        });
        if (schema.has("description") && !schema.get("description").isTextual()) fail(path, "description 必须是字符串");
        JsonNode type = schema.get("type");
        if (type != null) {
            List<JsonNode> types = new ArrayList<>();
            if (type.isArray()) type.forEach(types::add); else types.add(type);
            if (types.isEmpty()) fail(path, "type 不能为空");
            for (JsonNode value : types) {
                if (!value.isTextual() || !TYPES.contains(value.asText())) fail(path, "未知 type " + value);
            }
            // 本实现只支持单个具体类型与 null 的组合；复杂联合必须显式作为动态节点。
            if (types.stream().filter(t -> !"null".equals(t.asText())).count() > 1) {
                fail(path, "仅支持单类型或该类型与 null 的组合");
            }
        }
        if (hasType(schema, "object")) {
            if (!schema.path("properties").isObject() || !schema.path("additionalProperties").isBoolean()) {
                fail(path, "object 必须显式配置 properties 和布尔型 additionalProperties");
            }
            schema.get("properties").fields().forEachRemaining(e -> validateSchema(e.getValue(), path + "." + e.getKey()));
        } else if (schema.has("properties") || schema.has("additionalProperties")) {
            fail(path, "properties/additionalProperties 仅用于 object");
        }
        if (hasType(schema, "array")) {
            if (!schema.has("items")) fail(path, "array 必须配置 items");
            JsonNode items = schema.get("items");
            if (!(items.isBoolean() && !items.asBoolean())) validateSchema(items, path + "[]");
            if (schema.has("prefixItems")) {
                if (!schema.get("prefixItems").isArray() || schema.get("prefixItems").isEmpty()) {
                    fail(path, "prefixItems 必须是非空数组");
                }
                for (JsonNode item : schema.get("prefixItems")) validateSchema(item, path + "[列]");
            }
        } else if (schema.has("items") || schema.has("prefixItems")) {
            fail(path, "items/prefixItems 仅用于 array");
        }
    }

    private static void fail(String path, String message) {
        throw new IllegalArgumentException(path + ": " + message);
    }

    /** ref.path 与 ReferenceResolver 一致：空路径取整个 data；.error 是信封的错误字段。 */
    public static String referenceError(JsonNode schema, String path) {
        if (path == null || path.isEmpty() || path.equals(".data") || path.equals(".error")) return null;
        if (!path.startsWith(".data.")) return "输出根路径应为 data，字段路径不能省略 data";
        String current = "data";
        for (String segment : path.substring(6).split("\\.", -1)) {
            if (!schema.has("type")) return null; // 动态形状：不能以未知当作不存在。
            if (hasType(schema, "object")) {
                JsonNode child = schema.path("properties").get(segment);
                if (child == null) {
                    if (schema.path("additionalProperties").asBoolean()) return null;
                    return current + " 没有字段 " + segment + "；可用字段："
                            + String.join(", ", fieldNames(schema.path("properties")));
                }
                schema = child;
            } else if (hasType(schema, "array")) {
                // Jackson JSON Pointer 的数组下标不接受负数、前导零或 int 溢出。
                int index;
                try {
                    if (!segment.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    return current + " 是数组，须使用非负整数下标（如 .0），不能用 " + segment;
                }
                JsonNode prefix = schema.path("prefixItems");
                if (index < prefix.size()) {
                    schema = prefix.get(index);
                } else {
                    JsonNode items = schema.get("items");
                    if (items.isBoolean()) return current + " 的固定列下标只能为 0.." + (prefix.size() - 1);
                    schema = items;
                }
            } else {
                return current + " 是 " + schema.path("type") + "，不能继续引用 " + segment;
            }
            current += "." + segment;
        }
        return null;
    }

    /** 紧凑展示层级和固定列，避免向 Planner 注入重复的 schema 关键字。 */
    public static String describe(JsonNode schema) {
        String shape;
        if (hasType(schema, "object")) {
            List<String> fields = new ArrayList<>();
            schema.path("properties").fields().forEachRemaining(e -> fields.add(e.getKey() + ":" + describe(e.getValue())));
            if (schema.path("additionalProperties").asBoolean()) fields.add("其他动态字段");
            shape = "{" + String.join(", ", fields) + "}";
        } else if (hasType(schema, "array")) {
            if (schema.has("prefixItems")) {
                List<String> columns = new ArrayList<>();
                for (int i = 0; i < schema.get("prefixItems").size(); i++) {
                    columns.add(i + ":" + describe(schema.get("prefixItems").get(i)));
                }
                shape = "[" + String.join(", ", columns) + "]";
            } else {
                shape = "array<" + (schema.get("items").isObject() ? describe(schema.get("items")) : "无元素") + ">";
            }
        } else {
            shape = schema.has("type") ? schema.get("type").asText(schema.get("type").toString()) : "动态形状";
        }
        String description = schema.path("description").asText("");
        return description.isBlank() ? shape : shape + "(" + description + ")";
    }

    private static boolean hasType(JsonNode schema, String type) {
        JsonNode value = schema.path("type");
        if (value.isTextual()) return type.equals(value.asText());
        for (JsonNode element : value) if (type.equals(element.asText())) return true;
        return false;
    }

    private static List<String> fieldNames(JsonNode properties) {
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
