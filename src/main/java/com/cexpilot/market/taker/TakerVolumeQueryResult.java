package com.cexpilot.market.taker;

import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;

import java.util.List;

/**
 * 一次 taker 成交量统计查询的完整结果。
 *
 * @param requested 用户原始请求区间（TimeSpec 消解结果，未外扩）
 * @param effective 经 SeriesQueryPolicy 对齐 5m 网格后的实际执行区间
 * @param points    通过覆盖核对的 5m taker 成交量点（升序）
 * @param coverage  覆盖核对结果；rangeComplete=false 时不得输出区间统计
 */
public record TakerVolumeQueryResult(TimeRange requested,
                                     TimeRange effective,
                                     List<TakerVolumePoint> points,
                                     SeriesCoverage coverage) {
}
