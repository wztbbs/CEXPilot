package com.cexpilot.conversation;

import com.cexpilot.dag.ClarificationField;
import java.util.List;
import java.util.Map;

/** 用户对已展示表单的填写值。只作为查询条件，不是行情事实或可执行计划。 */
public record ClarificationAnswer(String key, String label, com.fasterxml.jackson.databind.JsonNode value) {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public ClarificationAnswer(String key, String label, String value) {
        this(key, label, com.fasterxml.jackson.databind.node.TextNode.valueOf(value));
    }

    public ClarificationAnswer { value = ClarificationField.parseValue(value); }

    public static List<ClarificationAnswer> validate(String parentTurnId, List<QueryRecord> history,
                                                    Map<String, ?> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (parentTurnId == null || parentTurnId.isBlank()) throw new IllegalArgumentException("表单提交必须携带父轮次");
        var parent = history.stream().filter(q -> parentTurnId.equals(q.turnId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到该轮澄清表单，请刷新历史"));
        var fields = ClarificationField.fromPayload(parent.planning());
        if (!"NEEDS_CLARIFICATION".equals(parent.turnStatus()) || fields.isEmpty()
                || values.size() != fields.size()) throw new IllegalArgumentException("填写项与该轮表单不一致");
        return fields.stream().map(field -> {
            com.fasterxml.jackson.databind.JsonNode value = ClarificationField.parseValue(JSON.valueToTree(values.get(field.key())));
            if (value == null) throw new IllegalArgumentException("请填写有效的" + field.label());
            if ("multiselect".equals(field.type())) {
                if (!value.isArray()) throw new IllegalArgumentException(field.label() + "必须以数组提交多选值");
                if (value.size() < field.minSelections() || value.size() > field.maxSelections())
                    throw new IllegalArgumentException(field.label() + "需要选择" + field.minSelections() + "～" + field.maxSelections() + "项");
                for (var selected : value) {
                    if (!field.options().contains(selected.asText())) throw new IllegalArgumentException(field.label() + "包含不在选项中的值");
                }
            } else {
                if (value.isArray() || value.asText().isBlank()) throw new IllegalArgumentException("请填写有效的" + field.label());
                value = com.fasterxml.jackson.databind.node.TextNode.valueOf(value.asText().trim());
            }
            // 校验表单身份与请求结构，不把“已填写”当成“值正确”。业务有效性仍由规划/编译判断，
            // 无效值也必须能进入纠错澄清，并向用户解释原因，而非仅返回一个 HTTP 400。
            return new ClarificationAnswer(field.key(), field.label(), value);
        }).toList();
    }

    public static String render(List<ClarificationAnswer> answers) {
        if (answers.isEmpty()) return "";
        return "\n【已填写的澄清条件】下列是用户提供的原值，不代表已验证正确。本轮明确修改优先。\n"
                + "仅未提供的条件用 reason=missing；值无效或含糊时允许再次询问，但必须 reason=invalid/confirm 并用 message 具体解释问题。"
                + "不能只因缺计划重复问币种；可明确识别的常用名可归一化（比特币为BTC）。\n"
                + answers.stream().map(a -> a.key() + "（" + a.label() + "）=" + (a.value() == null ? "" : a.value().isArray() ? a.value().toString() : a.value().asText()))
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
    }
}
