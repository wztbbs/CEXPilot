package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.model.OrderBook;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * 盘口快照选择器。只暴露价格类与买卖占比：挂单量两所单位不同（基础币 / 合约张数），
 * 不提供数量类指标，避免把张数和币数放在一起比较。
 */
public enum OrderBookSnapshot implements MetricSelector {
    BEST_BID(book -> book.bids().isEmpty() ? null : book.bids().get(0).price()),
    BEST_ASK(book -> book.asks().isEmpty() ? null : book.asks().get(0).price()),
    SPREAD(MarketCalculator::spread),
    /** 买卖盘不平衡度：按实际可用档数计算，不是固定 10 档。 */
    IMBALANCE(book -> MarketCalculator.orderbookImbalance(book,
            Math.min(OrderBookSnapshot.IMBALANCE_LEVELS, Math.min(book.bids().size(), book.asks().size()))));

    private final Function<OrderBook, BigDecimal> value;

    OrderBookSnapshot(Function<OrderBook, BigDecimal> value) { this.value = value; }

    /** 允许返回 null：盘口一侧为空时由 Provider 输出 Omitted，不补零。 */
    public BigDecimal value(OrderBook book) { return value.apply(book); }

    public static List<String> names() {
        return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    @Override public boolean supports(String shape) { return QueryShape.SNAPSHOT.code().equals(shape); }

    /** 与旧盘口输出一致的档数上限；不足时按实际档数计算。 */
    private static final int IMBALANCE_LEVELS = 10;
}
