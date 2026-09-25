package com.cexpilot.market.funding;

import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;

import java.util.List;

/**
 * 一次资金费率历史查询的完整结果。
 *
 * @param range      TimeSpec 消解出的查询区间（结算时刻落在此区间内）
 * @param points     通过覆盖核对的已结算费率（升序）
 * @param coverage   覆盖核对结果
 * @param intervalMs 该合约的结算周期（毫秒）
 */
public record FundingQueryResult(TimeRange range,
                                 List<FundingRatePoint> points,
                                 SeriesCoverage coverage,
                                 long intervalMs) {
}
