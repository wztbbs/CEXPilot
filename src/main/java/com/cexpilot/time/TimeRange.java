package com.cexpilot.time;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 确定的查询区间：[startInclusive, endExclusive)。
 * 由 TimeRangeResolver 把 LLM 解析出的 TimeSpec 结合 requestTime 换算得到，
 * 下游 tool 只面对这个对象，不再接触任何模糊时间表达。
 *
 * @param startInclusive 区间起点（含）
 * @param endExclusive   区间终点（不含）
 * @param timezone       消解该区间时使用的时区，供结果渲染保持一致
 */
public record TimeRange(Instant startInclusive, Instant endExclusive, ZoneId timezone) {

    public TimeRange {
        if (startInclusive == null || endExclusive == null || timezone == null) {
            throw new IllegalArgumentException("TimeRange 各字段不能为空");
        }
        if (!endExclusive.isAfter(startInclusive)) {
            throw new IllegalArgumentException(
                    "区间终点必须晚于起点: start=" + startInclusive + ", end=" + endExclusive);
        }
    }
}
