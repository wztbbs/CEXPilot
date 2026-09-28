package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** avg/sum/min/max 共用的集合输入解析：kind=values 数值集合，或 kind=field 对象字段 / 表格列。 */
final class Series {
    static final int MAX_VALUES = 10_000;

    /** index 是元素在输入集合中的 0 基位置；row 是原始元素（对象、数组行或标量）。 */
    record Element(int index, BigDecimal value, JsonNode row) {
    }

    private final String kind;
    private final List<Element> elements;

    private Series(String kind, List<Element> elements) {
        this.kind = kind;
        this.elements = elements;
    }

    String kind() {
        return kind;
    }

    List<Element> elements() {
        return elements;
    }

    List<BigDecimal> values() {
        return elements.stream().map(Element::value).toList();
    }

    /** 规划期允许整体引用（返回空元素列表）；执行期必须是解析后的真实值。 */
    static Series parse(JsonNode args, boolean allowReferences) {
        JsonNode input = CalculationTool.input(args);
        String kind = input.path("kind").asText();
        JsonNode rows;
        String field = null;
        Integer column = null;
        boolean deferredColumn = false;
        if ("values".equals(kind)) {
            CalculationTool.object(input, "input", Set.of("kind", "values"));
            rows = input.get("values");
        } else if ("field".equals(kind)) {
            CalculationTool.object(input, "input", Set.of("kind", "collection", "field", "columns"));
            JsonNode fieldNode = input.get("field");
            if (fieldNode == null || !fieldNode.isTextual() || fieldNode.asText().isBlank()) {
                throw new IllegalArgumentException("field 必须是非空字段名（不支持嵌套路径）");
            }
            field = fieldNode.asText();
            if (field.contains("{{")) throw new IllegalArgumentException("field 必须是确定的字段名");
            rows = input.get("collection");
            if (input.has("columns")) {
                JsonNode columns = input.get("columns");
                deferredColumn = CalculationTool.reference(columns, allowReferences);
                if (!deferredColumn) column = columnIndex(columns, field);
            }
        } else {
            throw new IllegalArgumentException("input.kind 必须是 values 或 field");
        }
        if (CalculationTool.reference(rows, allowReferences)) return new Series(kind, List.of());
        if (rows == null || !rows.isArray() || rows.isEmpty() || rows.size() > MAX_VALUES) {
            throw new IllegalArgumentException("输入集合必须是非空数组，最多 " + MAX_VALUES + " 个元素");
        }
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            JsonNode value = row;
            if (field != null) {
                if (CalculationTool.reference(row, allowReferences)) continue;
                if (input.has("columns")) {
                    if (!row.isArray()) throw new IllegalArgumentException("提供 columns 时每行必须是数组");
                    if (deferredColumn) continue;
                    if (row.size() != input.get("columns").size()) {
                        throw new IllegalArgumentException("第 " + i + " 行长度与 columns 不一致");
                    }
                    value = row.get(column);
                } else {
                    if (!row.isObject()) throw new IllegalArgumentException("对象集合每行必须是对象；表格数组需提供 columns");
                    value = row.get(field);
                }
            }
            BigDecimal number = CalculationTool.decimal(value, "第 " + i + " 个值", allowReferences);
            if (number != null) elements.add(new Element(i, number, row));
        }
        return new Series(kind, elements);
    }

    private static int columnIndex(JsonNode columns, String field) {
        if (columns == null || !columns.isArray() || columns.isEmpty()) {
            throw new IllegalArgumentException("columns 必须是非空列名数组");
        }
        Set<String> names = new HashSet<>();
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            JsonNode name = columns.get(i);
            if (!name.isTextual() || name.asText().isBlank() || !names.add(name.asText())) {
                throw new IllegalArgumentException("columns 列名必须为非空字符串且不可重复");
            }
            if (field.equals(name.asText())) found = i;
        }
        if (found < 0) throw new IllegalArgumentException("columns 不包含字段: " + field);
        return found;
    }
}
