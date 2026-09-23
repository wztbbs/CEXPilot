package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * OKX 当前资金费率快照。
 *
 * @param rate             当前预测费率（小数形式），结算时刻才最终确定
 * @param fundingTime      该预测费率生效的结算时间戳（毫秒），即下一次结算
 * @param nextFundingTime  再下一期预计结算时间戳（毫秒）
 * @param ts               快照采集时间戳（毫秒）
 */
public record FundingSnapshot(BigDecimal rate, long fundingTime, long nextFundingTime, long ts) {
}
