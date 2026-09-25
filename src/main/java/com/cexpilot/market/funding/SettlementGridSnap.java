package com.cexpilot.market.funding;

/**
 * 结算时间网格吸附：交易所结算时间戳可能偏离结算网格数毫秒（实测币安 +1ms），
 * 容差内吸附到最近的网格点；超出容差说明不是抖动，保持原值由覆盖核对报错位。
 */
final class SettlementGridSnap {

    static final long TOLERANCE_MS = 60_000L;

    private SettlementGridSnap() {
    }

    static long snap(long fundingTimeMs, long intervalMs) {
        long nearest = Math.round((double) fundingTimeMs / intervalMs) * intervalMs;
        return Math.abs(fundingTimeMs - nearest) <= TOLERANCE_MS ? nearest : fundingTimeMs;
    }
}
