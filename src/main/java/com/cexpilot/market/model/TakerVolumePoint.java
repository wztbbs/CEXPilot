package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 交易所官方 taker 统计的一个周期点（币安 takerlongshortRatio / OKX taker-volume-contract）。
 *
 * @param timestamp  周期起始时间戳（毫秒，5m 网格对齐）
 * @param buyVolume  该周期主动买成交量，单位为基础币
 * @param sellVolume 该周期主动卖成交量，单位为基础币
 */
public record TakerVolumePoint(long timestamp, BigDecimal buyVolume, BigDecimal sellVolume) {
}
