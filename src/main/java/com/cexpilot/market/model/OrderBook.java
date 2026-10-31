package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单簿（盘口深度）快照。
 *
 * @param bids      买单档位，按价格从高到低排序，
 *                  例如 [(65200.0, 1.5), (65199.5, 0.8)]
 * @param asks      卖单档位，按价格从低到高排序，
 *                  例如 [(65200.5, 2.0), (65201.0, 1.2)]
 * @param qtyUnit   档位数量单位：base（标的币种，Binance）或
 *                  contracts（合约张数，OKX 永续，未按合约规格换算）
 * @param timestamp 交易所数据时间戳（毫秒），0 表示上游未提供
 * @param collectedAt 本地采集时间（毫秒），与交易所数据时间独立；0 表示未记录
 */
public record OrderBook(List<Level> bids, List<Level> asks, String qtyUnit, long timestamp, long collectedAt) {
    public OrderBook(List<Level> bids, List<Level> asks, String qtyUnit, long timestamp) {
        this(bids, asks, qtyUnit, timestamp, 0);
    }

    /**
     * 一个价格档位。
     *
     * @param price 该档价格，例如 65200.50
     * @param qty   该档挂单数量，单位见所属 OrderBook 的 qtyUnit
     */
    public record Level(BigDecimal price, BigDecimal qty) {
    }
}
