package com.cexpilot.conversation;

import com.cexpilot.dag.ClarificationField;
import java.util.List;
import java.util.Map;

/** 用户对已展示表单的填写值。只作为查询条件，不是行情事实或可执行计划。 */
public record ClarificationAnswer(String key, String label, String value) {
    public static List<ClarificationAnswer> validate(String parentTurnId, List<QueryRecord> history,
                                                    Map<String, String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (parentTurnId == null || parentTurnId.isBlank()) throw new IllegalArgumentException("表单提交必须携带父轮次");
        var parent = history.stream().filter(q -> parentTurnId.equals(q.turnId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到该轮澄清表单，请刷新历史"));
        var fields = ClarificationField.fromPayload(parent.planning());
        if (!"NEEDS_CLARIFICATION".equals(parent.turnStatus()) || fields.isEmpty()
                || values.size() != fields.size()) throw new IllegalArgumentException("填写项与该轮表单不一致");
        return fields.stream().map(field -> {
            String raw = values.get(field.key());
            if (raw == null || raw.isBlank() || raw.length() > 200)
                throw new IllegalArgumentException("请填写有效的" + field.label());
            String value = raw.trim();
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
                + answers.stream().map(a -> a.key() + "（" + a.label() + "）=" + a.value())
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
    }
}
