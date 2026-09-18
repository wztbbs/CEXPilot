package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param currentRate     当前资金费率（小数形式，0.0001 = 0.01%）
 * @param nextFundingTime 下次结算时间戳（毫秒）
 * @param recentRates     最近若干次历史费率，按时间升序
 */
public record FundingInfo(BigDecimal currentRate, long nextFundingTime, List<BigDecimal> recentRates) {
}
