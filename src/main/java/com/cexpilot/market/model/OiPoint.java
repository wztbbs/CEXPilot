package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 持仓量历史的一个采样点。
 *
 * @param timestamp 采样时间戳（毫秒）
 * @param oi        未平仓数量，单位为基础币（两所口径已对齐）
 */
public record OiPoint(long timestamp, BigDecimal oi) {
}
