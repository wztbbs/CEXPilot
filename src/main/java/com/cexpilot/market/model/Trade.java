package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * @param buyAggressor 主动买方成交为 true（吃卖单）
 */
public record Trade(long time, BigDecimal price, BigDecimal qty, boolean buyAggressor) {
}
