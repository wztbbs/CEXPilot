package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单簿（盘口深度）快照。
 *
 * @param bids 买单档位，按价格从高到低排序，
 *             例如 [(65200.0, 1.5), (65199.5, 0.8)]
 * @param asks 卖单档位，按价格从低到高排序，
 *             例如 [(65200.5, 2.0), (65201.0, 1.2)]
 */
public record OrderBook(List<Level> bids, List<Level> asks) {

    /**
     * 一个价格档位。
     *
     * @param price 该档价格，例如 65200.50
     * @param qty   该档挂单数量（以标的币种计），例如 1.25 个 BTC
     */
    public record Level(BigDecimal price, BigDecimal qty) {
    }
}
