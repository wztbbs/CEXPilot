package com.cexpilot.market.funding;

import com.cexpilot.market.model.FundingRatePoint;

import java.util.List;

/**
 * 最近 N 期已结算费率（样本语义，不跑覆盖核对）。
 *
 * @param points     已结算费率（升序），不足 N 期时为实际取得的全部
 * @param singlePeriodMs 单期样本与前一条结算记录的间隔（毫秒）；多期或无法核实时为 null，不能用当前周期替代
 */
public record FundingRecentResult(List<FundingRatePoint> points, Long singlePeriodMs) {
}
