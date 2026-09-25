package com.cexpilot.market.oi;

import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.series.SeriesCoverage;

import java.util.List;

/**
 * 一次持仓量历史查询的完整结果。
 *
 * @param requested 用户原始要求（区间为 TimeSpec 消解结果，未外扩）
 * @param effective 经 SeriesQueryPolicy 对齐后的实际执行要求
 * @param points    通过覆盖核对的采样点（升序）
 */
public record OiQueryResult(OiQueryRequest requested,
                            OiQueryRequest effective,
                            List<OiPoint> points,
                            SeriesCoverage coverage) {
}
