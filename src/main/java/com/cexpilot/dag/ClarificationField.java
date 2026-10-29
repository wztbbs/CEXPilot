package com.cexpilot.dag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 面向用户的单个待补条件；不携带执行指令，填写结果仍经下一轮规划和计划校验。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClarificationField(String key, String label, String type, String placeholder,
                                 String hint, List<String> options, BigDecimal min, BigDecimal max,
                                 String reason, String message, JsonNode value,
                                 Integer minSelections, Integer maxSelections) {
    public ClarificationField(String key, String label, String type, String placeholder, String hint,
            List<String> options, BigDecimal min, BigDecimal max) {
        this(key, label, type, placeholder, hint, options, min, max, "missing", null, null, null, null);
    }

    public ClarificationField withValue(JsonNode previous) {
        return new ClarificationField(key, label, type, placeholder, hint, options, min, max, reason, message, previous, minSelections, maxSelections);
    }

    public ClarificationField {
        value = parseValue(value);
        reason = reason == null ? "missing" : reason;
        options = options == null ? List.of() : List.copyOf(options);
        if ("multiselect".equals(type)) {
            minSelections = minSelections == null ? 1 : minSelections;
            maxSelections = maxSelections == null ? options.size() : maxSelections;
        }
        min = min == null ? null : min.stripTrailingZeros();
        max = max == null ? null : max.stripTrailingZeros();
    }

    /** 整组校验，避免静默丢掉某个必要条件后展示不完整的表单。旧字符串由调用方兼容。 */
    public static List<ClarificationField> parse(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return List.of();
        if (node.size() > 8) throw new IllegalArgumentException("澄清项不能超过 8 项");
        var fields = new ArrayList<ClarificationField>();
        var keys = new HashSet<String>();
        for (JsonNode item : node) {
            String key = text(item, "key", 40, true);
            if (!key.matches("[A-Za-z][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("澄清字段 key 不合法: " + key);
            if (!keys.add(key)) throw new IllegalArgumentException("澄清字段 key 重复: " + key);
            String label = text(item, "label", 24, true);
            String type = text(item, "type", 16, true);
            if (!List.of("text", "number", "date", "select", "multiselect").contains(type))
                throw new IllegalArgumentException("不支持的澄清输入类型");
            var options = new ArrayList<String>();
            JsonNode values = item.path("options");
            if (!values.isMissingNode() && !values.isNull()) {
                if (!values.isArray() || values.size() > 12) throw new IllegalArgumentException("澄清选项格式错误");
                for (JsonNode value : values) {
                    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 80
                            || options.contains(value.asText())) throw new IllegalArgumentException("澄清选项不合法或重复");
                    options.add(value.asText());
                }
            }
            boolean choice = "select".equals(type) || "multiselect".equals(type);
            if (choice && options.isEmpty())
                throw new IllegalArgumentException("澄清字段 " + key + "（" + label
                        + "）的选择框缺少非空 options；必须提供具体选项，或改用 text 并说明填写内容");
            if (!choice && !options.isEmpty())
                throw new IllegalArgumentException("澄清字段 " + key + " 不是选择框，不能带选项");
            BigDecimal min = number(item, "min"), max = number(item, "max");
            if ((min != null || max != null) && !"number".equals(type)
                    || min != null && max != null && min.compareTo(max) > 0)
                throw new IllegalArgumentException("澄清数值范围不合法");
            String reason = text(item, "reason", 12, false);
            if (reason == null) reason = "missing"; // 旧字段兼容
            if (!List.of("missing", "invalid", "confirm").contains(reason))
                throw new IllegalArgumentException("不支持的澄清原因: " + reason);
            String message = text(item, "message", 200, !"missing".equals(reason));
            JsonNode value = parseValue(item.path("value"));
            Integer minSelections = selectionLimit(item, "minSelections"), maxSelections = selectionLimit(item, "maxSelections");
            if (!"multiselect".equals(type) && (minSelections != null || maxSelections != null))
                throw new IllegalArgumentException("只有多选框可指定选择数量");
            if ("multiselect".equals(type)) {
                int lower = minSelections == null ? 1 : minSelections;
                int upper = maxSelections == null ? options.size() : maxSelections;
                if (lower < 1 || upper < lower || upper > options.size())
                    throw new IllegalArgumentException("多选数量必须在1到选项数之间，且下限不能大于上限");
            }
            fields.add(new ClarificationField(key, label, type, text(item, "placeholder", 80, false),
                    text(item, "hint", 120, false), options, min, max, reason, message, value, minSelections, maxSelections));
        }
        return List.copyOf(fields);
    }

    /** 原值可为一个值或字符串数组；回显可跨输入类型，不能丢掉用户此前的多选。 */
    public static JsonNode parseValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isTextual() || value.isNumber()) {
            if (value.asText().length() > 200) throw new IllegalArgumentException("澄清回显值过长");
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(value.asText());
        }
        if (!value.isArray() || value.size() > 12) throw new IllegalArgumentException("澄清原值必须是文字、数值或字符串数组");
        var seen = new HashSet<String>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 200 || !seen.add(item.asText()))
                throw new IllegalArgumentException("多选原值必须是非空且不重复的文字");
        }
        return value.deepCopy();
    }

    private static Integer selectionLimit(JsonNode item, String key) {
        JsonNode value = item.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("多选数量必须是整数");
        return value.intValue();
    }

    private static String text(JsonNode item, String key, int max, boolean required) {
        JsonNode value = item.path(key);
        if (!required && (value.isMissingNode() || value.isNull())) return null;
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
            throw new IllegalArgumentException("澄清字段 " + key + " 必须是简短文字");
        return value.asText().trim();
    }

    private static BigDecimal number(JsonNode item, String key) {
        JsonNode value = item.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException("澄清数值范围必须是数字");
        return value.decimalValue();
    }

    /** 同一 payload 随普通响应、SSE 和历史记录返回。无表单的旧记录返回空列表。 */
    public static List<ClarificationField> fromPayload(JsonNode payload) {
        if (payload == null || !"NEEDS_CLARIFICATION".equals(payload.path("status").asText())) return List.of();
        try { return parse(payload.path("clarify")); }
        catch (IllegalArgumentException ignored) { return List.of(); }
    }

    public static String question(List<ClarificationField> fields) {
        return "请补充或确认以下信息：" + fields.stream().map(f -> f.label()
                + (f.message() != null ? "：" + f.message() : f.hint() == null ? "" : "（" + f.hint() + "）"))
                .collect(java.util.stream.Collectors.joining("；")) + "。";
    }
}
