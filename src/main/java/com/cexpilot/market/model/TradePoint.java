package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 一笔成交（历史序列用）。
 *
 * @param tradeId   交易所成交 ID：币安为聚合成交 ID（一笔聚合可能含多笔原始成交），OKX 为逐笔成交 ID
 * @param timestamp 成交时间戳（毫秒）
 * @param price     成交价格（USDT）
 * @param qty       成交数量（基础币；OKX 已按合约面值从张数换算）
 * @param takerBuy  主动方是否为买方（true=主动买入）
 */
public record TradePoint(String tradeId, long timestamp, BigDecimal price,
                         BigDecimal qty, boolean takerBuy) {
}
