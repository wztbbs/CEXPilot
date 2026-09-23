package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 永续合约标记价格信息。
 *
 * @param markPrice       标记价格（用于计算未实现盈亏和强平），
 *                        例如 65231.50
 * @param indexPrice      指数价格（现货指数加权价），例如 65210.30
 * @param fundingRate     当前资金费率（小数形式），
 *                        例如 0.0001 表示 0.01%
 * @param nextFundingTime 下次资金费结算时间戳（毫秒），
 *                        例如 1700035200000
 */
public record MarkPrice(BigDecimal markPrice,
                        BigDecimal indexPrice,
                        BigDecimal fundingRate,
                        long nextFundingTime) {
}
