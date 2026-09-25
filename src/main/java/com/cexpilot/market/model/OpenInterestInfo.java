package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 指定 USDT 永续合约的持仓量当前快照，是未平仓数量而非货币名义价值。
 *
 * @param oi           当前未平仓数量，单位为基础币；null 表示无法确定
 * @param unit         基础币代码，例如 BTC、ETH
 * @param dataTime     交易所给的数据时间戳（毫秒），0 表示未知
 * @param snapshotTime 快照采集时间戳（毫秒），0 表示未知
 */
public record OpenInterestInfo(BigDecimal oi, String unit, long dataTime, long snapshotTime) {
}
