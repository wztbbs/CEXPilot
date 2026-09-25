package com.cexpilot.market.funding;

import com.cexpilot.market.model.FundingRatePoint;

import java.util.List;

/**
 * 最近 N 期已结算费率（样本语义，不跑覆盖核对）。
 *
 * @param points     已结算费率（升序），不足 N 期时为实际取得的全部
 * @param intervalMs 该合约的结算周期（毫秒）
 */
public record FundingRecentResult(List<FundingRatePoint> points, long intervalMs) {
}
