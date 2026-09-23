package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 一笔逐笔成交记录。
 *
 * @param time         成交时间戳（毫秒），例如 1700000000123
 * @param price        成交价格，例如 65231.50
 * @param qty          成交数量，单位见 qtyUnit
 * @param buyAggressor 主动买方成交为 true（买方吃卖单，视为买盘）；
 *                     false 表示卖方吃买单，视为卖盘
 * @param qtyUnit      数量单位：base（标的币种，Binance）或
 *                     contracts（合约张数，OKX，未按合约规格换算）
 */
public record Trade(long time, BigDecimal price, BigDecimal qty, boolean buyAggressor, String qtyUnit) {
}
