package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 永续合约资金费率信息。
 *
 * @param currentRate                当前资金费率（小数形式），
 *                                   例如 0.0001 表示 0.01%，多方支付给空方；
 *                                   为负时（如 -0.0002）则空方支付给多方；null 表示无法确定
 * @param currentRateKind            当前费率状态：predicted（OKX 预测费率，
 *                                   到结算时刻才会确定）或 settled（Binance 最近一期已结算费率）
 * @param currentRateSettlementTime  当前费率对应的结算时间戳（毫秒）：
 *                                   OKX 为预测费率生效的结算时刻（fundingTime），
 *                                   Binance 为历史末条的结算时刻（费率与时间同源）；0 表示未知
 * @param nextFundingTime            下一次结算时间戳（毫秒），
 *                                   例如 1700035200000 对应 2023-11-15 08:00:00 UTC
 * @param followingFundingTime       再下一期预计结算时间戳（毫秒），0 表示不提供（Binance）
 * @param snapshotTime               当前费率快照的采集时间戳（毫秒），0 表示未知；
 *                                   表示数据是何时观察到的，与结算时间含义不同
 * @param recentRatesKind            历史费率口径：realized（OKX 实际结算值 realizedRate）
 *                                   或 settled（Binance 已结算费率）
 * @param recentRates                最近若干期历史费率，按时间升序
 */
public record FundingInfo(BigDecimal currentRate,
                          String currentRateKind,
                          long currentRateSettlementTime,
                          long nextFundingTime,
                          long followingFundingTime,
                          long snapshotTime,
                          String recentRatesKind,
                          List<RatePoint> recentRates) {

    /**
     * 一期资金费率。
     *
     * @param rate        费率（小数形式），例如 0.0001 表示 0.01%
     * @param fundingTime 该期结算时间戳（毫秒）
     */
    public record RatePoint(BigDecimal rate, long fundingTime) {
    }
}
