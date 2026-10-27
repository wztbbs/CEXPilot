package com.cexpilot.market.oi;

import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.series.SeriesCoverage;

import java.util.List;

/**
 * 一次持仓量历史查询的完整结果。
 *
 * @param requested 用户原始要求（区间为 TimeSpec 消解结果，未外扩）
 * @param effective 序列为网格外扩范围；端点统计为实际逻辑窗口，是否含终点由 mode 决定
 * @param points    通过覆盖核对的采样点（升序）
 */
public record OiQueryResult(OiQueryRequest requested,
                            OiQueryRequest effective,
                            List<OiPoint> points,
                            SeriesCoverage coverage) {
}
