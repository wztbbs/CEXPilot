package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合约持仓量（Open Interest）信息。
 *
 * @param currentOi 当前持仓量（USD 名义值），
 *                  例如 5234567890.00 表示约 52.3 亿美元
 * @param unit      计量单位（统一为 USD）
 * @param history   历史持仓量（与当前值同为 USD），按时间升序
 */
public record OpenInterestInfo(BigDecimal currentOi, String unit, List<OiPoint> history) {

    /**
     * 历史持仓量数据点。
     *
     * @param timestamp 采样时间戳（毫秒），例如 1700000000000
     * @param oi        该时刻的持仓量（USD 名义值），
     *                  例如 5200000000.00
     */
    public record OiPoint(long timestamp, BigDecimal oi) {
    }
}
