package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 一根 K 线（蜡烛图）数据。
 *
 * @param openTime 开盘时间戳（毫秒），例如 1700000000000
 *                 对应 2023-11-14 22:13:20 UTC
 * @param open     开盘价，例如 36500.00
 * @param high     本周期最高价，例如 36820.50
 * @param low      本周期最低价，例如 36410.00
 * @param close    收盘价（未收盘的 K 线为最新价），例如 36755.25
 * @param volume   成交量（以标的币种计），例如 152.34 个 BTC
 */
public record Candle(long openTime,
                     BigDecimal open,
                     BigDecimal high,
                     BigDecimal low,
                     BigDecimal close,
                     BigDecimal volume) {
}
