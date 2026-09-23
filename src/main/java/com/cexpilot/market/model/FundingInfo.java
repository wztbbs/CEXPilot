package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 永续合约资金费率信息。
 *
 * @param currentRate     当前资金费率（小数形式），
 *                        例如 0.0001 表示 0.01%，多方支付给空方；
 *                        为负时（如 -0.0002）则空方支付给多方
 * @param nextFundingTime 下次结算时间戳（毫秒），
 *                        例如 1700035200000 对应 2023-11-15 08:00:00 UTC
 * @param recentRates     最近若干次历史费率，按时间升序，
 *                        例如 [0.0001, 0.0001, -0.0002]
 */
public record FundingInfo(BigDecimal currentRate, long nextFundingTime, List<BigDecimal> recentRates) {
}
