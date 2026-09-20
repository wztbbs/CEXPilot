package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param currentOi 当前持仓量（USD 名义值）
 * @param unit      计量单位（统一为 USD）
 * @param history   历史持仓量（与当前值同为 USD），按时间升序
 */
public record OpenInterestInfo(BigDecimal currentOi, String unit, List<OiPoint> history) {

    public record OiPoint(long timestamp, BigDecimal oi) {
    }
}
