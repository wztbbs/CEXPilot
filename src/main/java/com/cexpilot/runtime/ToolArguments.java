package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 当前工具使用的扁平 JSON Schema 子集：类型、枚举、默认值、范围、正则、required。object 仅校验是对象，嵌套结构由执行器严格校验。 */
public final class ToolArguments {
    private static final Set<String> ROOT_KEYS = Set.of("type", "properties", "required", "additionalProperties");
    private static final Set<String> PROPERTY_KEYS = Set.of("type", "description", "enum", "default", "minimum", "maximum", "pattern");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "object");
    private static final Pattern REF = Pattern.compile("\\{\\{[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*}}");

    private ToolArguments() {}

    public static void validateSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())
                || !schema.path("properties").isObject()) {
            throw new IllegalArgumentException("input_schema 必须是含 properties 的 object schema");
        }
        checkKeys(schema, ROOT_KEYS);
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean()) {
            throw new IllegalArgumentException("additionalProperties 必须是布尔值");
        }
        JsonNode properties = schema.get("properties");
        if (schema.has("required")) {
            if (!schema.get("required").isArray()) throw new IllegalArgumentException("required 必须是数组");
            for (JsonNode key : schema.get("required")) {
                if (!key.isTextual() || !properties.has(key.asText())) {
                    throw new IllegalArgumentException("required 引用了未定义参数: " + key);
                }
            }
        }
        properties.fields().forEachRemaining(entry -> {
            JsonNode rule = entry.getValue();
            if (!rule.isObject() || !TYPES.contains(rule.path("type").asText())) {
                throw new IllegalArgumentException("参数类型不支持: " + entry.getKey());
            }
            checkKeys(rule, PROPERTY_KEYS);
            if (rule.has("pattern")) {
                if (!rule.get("pattern").isTextual() || !"string".equals(rule.path("type").asText())) {
                    throw new IllegalArgumentException("pattern 仅支持字符串参数");
                }
                Pattern.compile(rule.get("pattern").asText());
            }
            for (String bound : List.of("minimum", "maximum")) {
                if (rule.has(bound) && (!rule.get(bound).isNumber()
                        || !Set.of("number", "integer").contains(rule.path("type").asText()))) {
                    throw new IllegalArgumentException(bound + " 仅支持数值参数");
                }
            }
            if (rule.has("minimum") && rule.has("maximum")
                    && rule.get("minimum").decimalValue().compareTo(rule.get("maximum").decimalValue()) > 0) {
                throw new IllegalArgumentException("minimum 大于 maximum");
            }
            if (rule.has("enum")) {
                if (!rule.get("enum").isArray() || rule.get("enum").isEmpty()) {
                    throw new IllegalArgumentException("enum 必须是非空数组");
                }
                for (JsonNode value : rule.get("enum")) {
                    if (!typeMatches(value, rule.path("type").asText())) {
                        throw new IllegalArgumentException("enum 值类型错误: " + entry.getKey());
                    }
                }
            }
            if (rule.has("default")) {
                List<String> errors = new ArrayList<>();
                validateValue(entry.getKey(), rule.get("default"), rule, errors);
                if (!errors.isEmpty()) throw new IllegalArgumentException("非法 default: " + errors);
            }
        });
    }

    private static void checkKeys(JsonNode node, Set<String> supported) {
        node.fieldNames().forEachRemaining(key -> {
            if (!supported.contains(key)) throw new IllegalArgumentException("不支持的 schema 字段: " + key);
        });
    }

    public static ObjectNode withDefaults(JsonNode args, JsonNode schema) {
        if (args == null || !args.isObject()) throw new IllegalArgumentException("args 必须是对象");
        ObjectNode result = args.deepCopy();
        schema.path("properties").fields().forEachRemaining(entry -> {
            if (!result.has(entry.getKey()) && entry.getValue().has("default")) {
                result.set(entry.getKey(), entry.getValue().get("default").deepCopy());
            }
        });
        return result;
    }

    public static List<String> validate(JsonNode args, JsonNode schema, boolean allowReferences) {
        List<String> errors = new ArrayList<>();
        if (args == null || !args.isObject()) return List.of("args 必须是对象");
        JsonNode values = withDefaults(args, schema);
        for (JsonNode key : schema.path("required")) {
            if (!values.hasNonNull(key.asText())) errors.add("缺少必填参数: " + key.asText());
        }
        values.fields().forEachRemaining(entry -> {
            JsonNode rule = schema.path("properties").get(entry.getKey());
            if (rule == null) {
                if (!schema.path("additionalProperties").asBoolean(true)) errors.add("未知参数: " + entry.getKey());
            } else if (!(allowReferences && entry.getValue().isTextual()
                    && REF.matcher(entry.getValue().asText()).matches())) {
                validateValue(entry.getKey(), entry.getValue(), rule, errors);
            }
        });
        return errors;
    }

    private static boolean typeMatches(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            default -> false;
        };
    }

    private static void validateValue(String name, JsonNode value, JsonNode rule, List<String> errors) {
        if (!typeMatches(value, rule.path("type").asText())) {
            errors.add(name + " 类型必须为 " + rule.path("type").asText());
            return;
        }
        if (rule.has("enum")) {
            boolean found = false;
            for (JsonNode option : rule.get("enum")) if (option.equals(value)) found = true;
            if (!found) errors.add(name + " 不在枚举范围内");
        }
        if (value.isNumber()) {
            if (rule.has("minimum") && value.decimalValue().compareTo(rule.get("minimum").decimalValue()) < 0)
                errors.add(name + " 小于 minimum");
            if (rule.has("maximum") && value.decimalValue().compareTo(rule.get("maximum").decimalValue()) > 0)
                errors.add(name + " 大于 maximum");
        }
        if (rule.has("pattern") && !Pattern.compile(rule.get("pattern").asText()).matcher(value.asText()).find()) {
            errors.add(name + " 不符合格式 " + rule.get("pattern").asText());
        }
    }
}
