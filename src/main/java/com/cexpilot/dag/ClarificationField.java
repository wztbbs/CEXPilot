package com.cexpilot.dag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 面向用户的自由文本澄清项；options 仅供参考，不定义输入类型或执行约束。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClarificationField(String key, String label, String placeholder, String hint,
                                 List<String> options, String reason, String message, JsonNode value) {
    public ClarificationField {
        value = parseValue(value);
        reason = reason == null ? "missing" : reason;
        options = options == null ? List.of() : List.copyOf(options);
    }

    public ClarificationField withValue(JsonNode previous) {
        return new ClarificationField(key, label, placeholder, hint, options, reason, message, previous);
    }

    /** 只校验字段身份、说明和基本结构。旧 type/min/max/选择数量字段一律忽略。 */
    public static List<ClarificationField> parse(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return List.of();
        if (node.size() > 8) throw new IllegalArgumentException("澄清项不能超过 8 项");
        var fields = new ArrayList<ClarificationField>();
        var keys = new HashSet<String>();
        for (JsonNode item : node) {
            String key = text(item, "key", 40, true);
            if (!key.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("澄清字段 key 不合法: " + key);
            if (!keys.add(key)) throw new IllegalArgumentException("澄清字段 key 重复: " + key);
            String label = text(item, "label", 24, true);
            String reason = text(item, "reason", 12, false);
            if (reason == null) reason = "missing";
            if (!List.of("missing", "invalid", "confirm").contains(reason))
                throw new IllegalArgumentException("不支持的澄清原因: " + reason);
            String message = text(item, "message", 200, !"missing".equals(reason));
            // 参考项是可选展示信息，格式不佳也不能阻断用户填写真正需要的条件。
            var options = new ArrayList<String>();
            JsonNode refs = item.path("options");
            if (refs.isArray()) {
                for (JsonNode ref : refs) addReference(options, ref);
            } else if (refs.isTextual()) addReference(options, refs);
            fields.add(new ClarificationField(key, label, displayText(item.path("placeholder"), 80),
                    displayText(item.path("hint"), 120), options, reason, message, parseValue(item.path("value"))));
        }
        return List.copyOf(fields);
    }

    private static String displayText(JsonNode value, int max) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= max ? value.asText().trim() : null;
    }

    private static void addReference(List<String> options, JsonNode ref) {
        if (!ref.isTextual() || ref.asText().isBlank() || ref.asText().length() > 80 || options.size() >= 12) return;
        String value = ref.asText().trim();
        if (!options.contains(value)) options.add(value);
    }

    /** 新提交使用原始文本；兼容旧任务保存的数组，不按选项、数量或数值范围判断原值。 */
    public static JsonNode parseValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isTextual() || value.isNumber()) {
            if (value.asText().length() > 200) throw new IllegalArgumentException("澄清原值过长");
            return TextNode.valueOf(value.asText());
        }
        if (!value.isArray() || value.size() > 32) throw new IllegalArgumentException("澄清原值必须是文字、数值或旧版文字数组");
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().length() > 200)
                throw new IllegalArgumentException("旧版澄清数组只能包含文字");
        }
        return value.deepCopy();
    }

    private static String text(JsonNode item, String key, int max, boolean required) {
        JsonNode value = item.path(key);
        if (!required && (value.isMissingNode() || value.isNull())) return null;
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
            throw new IllegalArgumentException("澄清字段 " + key + " 必须是简短文字");
        return value.asText().trim();
    }

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
