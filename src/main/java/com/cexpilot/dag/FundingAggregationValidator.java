package com.cexpilot.dag;

import com.cexpilot.metric.CompiledPlan;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** 保守的需求覆盖检查：只处理明确“最近N期资金费率最高/最低”的完整序列需求，不猜测子集或追问。 */
final class FundingAggregationValidator {
    private static final Pattern RECENT = Pattern.compile("(?:最近|近)\\s*(\\d{1,3})\\s*期");
    private static final Pattern PERIOD = Pattern.compile("(\\d+)\\s*期");
    private static final Pattern SUBSET = Pattern.compile("第|前\\s*[\\d一二三四五六七八九十两]+\\s*期|后\\s*[\\d一二三四五六七八九十两]+\\s*期|首|末|最后|其中|只|仅|排除|去掉|除外|除去|不含|不要|不用|无需");

    List<String> check(String question, CompiledPlan plan) {
        if (question == null || !question.matches("(?is).*(资金费率|funding).*") || SUBSET.matcher(question).find()
                || question.matches("(?s).*(平均|均值|合计|总和|累计|年化|差值|之差|相差|倍数|比值).*")) return List.of();
        var recent = RECENT.matcher(question);
        if (!recent.find()) return List.of();
        int count = Integer.parseInt(recent.group(1));
        if (count <= 1) return List.of();
        var periods = PERIOD.matcher(question);
        while (periods.find()) if (!new java.math.BigInteger(periods.group(1)).equals(java.math.BigInteger.valueOf(count))) return List.of();
        var required = new ArrayList<String>();
        if (question.contains("最高") || question.contains("最大")) required.add("max");
        if (question.contains("最低") || question.contains("最小")) required.add("min");
        if (required.isEmpty()) return List.of();
        var errors = new ArrayList<String>();
        for (var target : plan.targets()) {
            if (!"funding.rate_settled".equals(target.metric()) || !"recent_n".equals(target.queryShape())) continue;
            String branch = target.groupId() + "." + target.exchange();
            if (!Objects.equals(count, target.count())) {
                errors.add("完整序列聚合需求未覆盖：" + branch + " 应取最近" + count + "期，不能改成" + target.count() + "期");
                continue;
            }
            for (String operator : required) {
                var calculations = plan.logicalPlan().calculations().stream().filter(c -> operator.equals(c.operator())).toList();
                if (calculations.stream().anyMatch(c -> covers(c.input(), branch, count))) continue;
                errors.add("完整序列聚合需求未覆盖：" + branch + " 最近" + count + "期"
                        + (calculations.isEmpty() ? "缺少 " + operator + " 算子" : "的 " + operator + " 未绑定全部样本，不能用部分样本代表全体")
                        + "；请生成 " + operator + "，input={\"kind\":\"field\",\"collection\":\"{{" + branch
                        + ".samples}}\",\"field\":\"value\"}，最高与最低必须分别生成，item 保留对应期次");
            }
        }
        return errors;
    }

    private static boolean covers(JsonNode input, String branch, int count) {
        if ("field".equals(input.path("kind").asText()))
            return ("{{" + branch + ".samples}}").equals(input.path("collection").asText())
                    && "value".equals(input.path("field").asText());
        // 完整枚举所有期次同样覆盖全体；不一律禁止合法的下标引用。
        JsonNode values = input.path("values");
        if (!"values".equals(input.path("kind").asText()) || !values.isArray() || values.size() != count) return false;
        Set<String> expected = new HashSet<>(), actual = new HashSet<>();
        for (int i=0;i<count;i++) expected.add("{{" + branch + ".samples." + i + ".value}}");
        values.forEach(value -> actual.add(value.asText()));
        return expected.equals(actual);
    }
}
