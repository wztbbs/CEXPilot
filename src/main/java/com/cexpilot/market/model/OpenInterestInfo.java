package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 指定 USDT 永续合约的未平仓数量，不是货币名义价值。
 *
 * @param currentOi 历史序列最新采样数量；历史为空时为 null，不代表实时快照
 * @param unit 基础币代码，例如 BTC、ETH；当前值和历史值均使用该单位
 * @param history 按时间升序的样本，不保证覆盖完整 24 小时
 */
public record OpenInterestInfo(BigDecimal currentOi, String unit, List<OiPoint> history) {
    /** @param timestamp 采样时间戳（毫秒）
     *  @param oi 未平仓数量，单位与外层 unit 一致
     */
    public record OiPoint(long timestamp, BigDecimal oi) {}
}
