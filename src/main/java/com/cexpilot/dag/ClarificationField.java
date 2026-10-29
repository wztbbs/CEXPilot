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
                                 String hint, List<String> options, BigDecimal min, BigDecimal max) {
    public ClarificationField {
        options = options == null ? List.of() : List.copyOf(options);
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
            String type = text(item, "type", 10, true);
            if (!List.of("text", "number", "date", "select").contains(type))
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
            if ("select".equals(type) && options.isEmpty())
                throw new IllegalArgumentException("澄清字段 " + key + "（" + label
                        + "）的 select 缺少非空 options；必须提供具体选项，或改用 text 并说明填写内容");
            if (!"select".equals(type) && !options.isEmpty())
                throw new IllegalArgumentException("澄清字段 " + key + " 不是选择框，不能带选项");
            BigDecimal min = number(item, "min"), max = number(item, "max");
            if ((min != null || max != null) && !"number".equals(type)
                    || min != null && max != null && min.compareTo(max) > 0)
                throw new IllegalArgumentException("澄清数值范围不合法");
            fields.add(new ClarificationField(key, label, type, text(item, "placeholder", 80, false),
                    text(item, "hint", 120, false), options, min, max));
        }
        return List.copyOf(fields);
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
        return "请补充以下信息：" + fields.stream().map(f -> f.label()
                + (f.hint() == null ? "" : "（" + f.hint() + "）")).collect(java.util.stream.Collectors.joining("；")) + "。";
    }
}
