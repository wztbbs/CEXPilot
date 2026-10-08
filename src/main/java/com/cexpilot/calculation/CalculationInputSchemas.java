package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 规划期结构契约；引用允许延迟解析，数值/单位等语义仍由执行校验负责。 */
final class CalculationInputSchemas {
    private CalculationInputSchemas() {}

    static ObjectNode object() {
        var schema = CalculationTool.MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties");
        schema.putArray("required");
        return schema;
    }
    static ObjectNode choice(String... choices) {
        var schema = CalculationTool.MAPPER.createObjectNode().put("type", "string");
        for (String value : choices) schema.withArray("enum").add(value);
        return schema;
    }
    static ObjectNode reference() {
        return CalculationTool.MAPPER.createObjectNode().put("type", "string")
                .put("pattern", "^\\{\\{[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*}}$");
    }
    static ObjectNode any(JsonNode... alternatives) {
        var schema = CalculationTool.MAPPER.createObjectNode();
        for (JsonNode alternative : alternatives) schema.withArray("anyOf").add(alternative);
        return schema;
    }
    static ObjectNode number() {
        return any(CalculationTool.MAPPER.createObjectNode().put("type", "number"),
                CalculationTool.MAPPER.createObjectNode().put("type", "string")
                        .put("pattern", "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$"), reference());
    }
    static void field(ObjectNode schema, String key, JsonNode value, boolean required) {
        ((ObjectNode) schema.get("properties")).set(key, value);
        if (required) schema.withArray("required").add(key);
    }
    static ObjectNode array(JsonNode items) {
        var schema = CalculationTool.MAPPER.createObjectNode().put("type", "array")
                .put("minItems", 1).put("maxItems", Series.MAX_VALUES);
        schema.set("items", items);
        return schema;
    }
    static JsonNode series() {
        var values = object();
        field(values, "kind", choice("values"), true);
        field(values, "values", any(reference(), array(number())), true);
        var rows = object();
        field(rows, "kind", choice("field"), true);
        // 对象的属性名由用户选择；表格列仍兼容执行器原有契约。
        var row = any(CalculationTool.MAPPER.createObjectNode().put("type", "object"),
                array(number()), reference());
        field(rows, "collection", any(reference(), array(row)), true);
        field(rows, "field", CalculationTool.MAPPER.createObjectNode().put("type", "string").put("minLength", 1), true);
        field(rows, "columns", any(reference(), array(CalculationTool.MAPPER.createObjectNode()
                .put("type", "string").put("minLength", 1))), false);
        return any(values, rows);
    }
    static JsonNode binary(String left, String right) {
        var schema = object();
        field(schema, left, number(), true);
        field(schema, right, number(), true);
        return schema;
    }
    static JsonNode annualize() {
        var schema = object();
        field(schema, "basis", choice("holding_return", "periodic_rate", "cumulative_rate"), true);
        field(schema, "method", choice("simple", "compound"), true);
        field(schema, "rate", number(), true);
        field(schema, "rate_unit", choice("ratio", "percent"), true);
        var period = object();
        field(period, "value", number(), true);
        field(period, "unit", choice("second", "minute", "hour", "day"), true);
        field(schema, "period", period, true);
        var days = CalculationTool.MAPPER.createObjectNode().put("type", "integer");
        for (int day : List.of(360, 365, 366)) days.withArray("enum").add(day);
        field(schema, "year_days", days, false);
        return schema;
    }
}
