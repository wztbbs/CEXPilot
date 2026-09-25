package com.cexpilot.market.series;

import java.util.List;

/**
 * 历史序列覆盖核对结果。完整性以时间位置比对为准，数量仅用于快速发现异常。
 *
 * @param expectedCount    预期数据点数（要求已完结时已剔除未完结点）
 * @param actualCount      通过核对的实际点数
 * @param missing          预期但未出现的时间戳（ms）
 * @param unexpected       错位/多出的时间戳（ms）
 * @param duplicates       重复的时间戳（ms），适配器已去重，此处为防御性检测
 * @param containsUnclosed 通过核对的点中是否含未完结点（仅 includeUnclosed=true 时可能为 true）
 * @param droppedUnclosed  是否因要求已完结而剔除了未完结点
 * @param abortReason      数据源分页中止原因；非 null 时结果为部分数据
 * @param coveredUntilMs   数据实际覆盖终点（不含，ms）；null 表示没有任何通过核对的点。
 *                         区间延伸到 requestTime 之后时，该值明显小于请求终点
 * @param rangeBeyondNow   请求区间终点是否超过 requestTime（如"今天全天"在中午查询）：
 *                         未来部分永远无法覆盖，结果必然是部分覆盖
 */
public record SeriesCoverage(int expectedCount,
                             int actualCount,
                             List<Long> missing,
                             List<Long> unexpected,
                             List<Long> duplicates,
                             boolean containsUnclosed,
                             boolean droppedUnclosed,
                             String abortReason,
                             Long coveredUntilMs,
                             boolean rangeBeyondNow) {

    /** 已完结部分齐全 = 无缺失、无错位、无重复、分页未被中止。 */
    public boolean complete() {
        return missing.isEmpty() && unexpected.isEmpty() && duplicates.isEmpty() && abortReason == null;
    }

    /** 请求区间完整 = 已完结部分齐全，且区间不延伸到 requestTime 之后。 */
    public boolean rangeComplete() {
        return complete() && !rangeBeyondNow;
    }
}
