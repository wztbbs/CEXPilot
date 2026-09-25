package com.cexpilot.market.policy;

import com.fasterxml.jackson.databind.JsonNode;

/** 样本参数检查；调用方声明自身上限，不在 plan 中维护工具白名单。 */
public final class SampleQueryPolicy {
    private SampleQueryPolicy() {}

    public static int count(JsonNode args, String field, Integer defaultValue, int maximum) {
        JsonNode value = args.get(field);
        if (value == null && defaultValue != null) return check(defaultValue, maximum);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        return check(value.intValue(), maximum);
    }

    public static int check(int count, int maximum) {
        if (count <= 0 || count > maximum) {
            throw new IllegalArgumentException("样本条数必须在 1～" + maximum + " 之间，实际为 " + count);
        }
        return count;
    }
}
