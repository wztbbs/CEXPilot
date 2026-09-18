package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param currentOi 当前持仓量（以基础币计，如 BTC）
 * @param unit      计量单位
 * @param history   历史持仓量，按时间升序
 */
public record OpenInterestInfo(BigDecimal currentOi, String unit, List<OiPoint> history) {

    public record OiPoint(long timestamp, BigDecimal oi) {
    }
}
