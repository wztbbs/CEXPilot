package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 一期已结算资金费率。
 *
 * @param rate        费率（小数形式），例如 0.0001 表示 0.01%；为负时空方支付给多方
 * @param fundingTime 该期结算时间戳（毫秒）
 */
public record FundingRatePoint(BigDecimal rate, long fundingTime) {
}
