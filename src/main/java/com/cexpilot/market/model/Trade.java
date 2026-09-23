package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 一笔逐笔成交记录。
 *
 * @param time         成交时间戳（毫秒），例如 1700000000123
 * @param price        成交价格，例如 65231.50
 * @param qty          成交数量（以标的币种计），例如 0.015 个 BTC
 * @param buyAggressor 主动买方成交为 true（买方吃卖单，视为买盘）；
 *                     false 表示卖方吃买单，视为卖盘
 */
public record Trade(long time, BigDecimal price, BigDecimal qty, boolean buyAggressor) {
}
