package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.OpenInterestInfo.OiPoint;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.Trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 所有金融数字的确定性计算都在这里，LLM 不允许自己算。
 * 返回给 LLM 的事实只来自这些方法的计算结果。
 */
public final class MarketCalculator {

    private MarketCalculator() {
    }

    /** 窗口内价格变化：首根开盘价 → 末根收盘价。 */
    public record PriceChange(BigDecimal startPrice, BigDecimal endPrice,
                              BigDecimal changePct, BigDecimal high, BigDecimal low,
                              int candleCount) {
    }

    public static PriceChange priceChange(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        BigDecimal start = candles.get(0).open();
        BigDecimal end = candles.get(candles.size() - 1).close();
        BigDecimal high = candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(end);
        BigDecimal low = candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(end);
        BigDecimal changePct = pctChange(start, end);
        return new PriceChange(start, end, changePct, high, low, candles.size());
    }

    /** 资金费率趋势：后半段均值 vs 前半段均值。 */
    public static String fundingTrend(List<BigDecimal> recentRates) {
        if (recentRates == null || recentRates.size() < 4) {
            return "unknown";
        }
        int mid = recentRates.size() / 2;
        BigDecimal firstHalf = avg(recentRates.subList(0, mid));
        BigDecimal secondHalf = avg(recentRates.subList(mid, recentRates.size()));
        BigDecimal diff = secondHalf.subtract(firstHalf).abs();
        // 0.005% 视为噪声
        if (diff.compareTo(new BigDecimal("0.00005")) < 0) {
            return "flat";
        }
        return secondHalf.compareTo(firstHalf) > 0 ? "rising" : "falling";
    }

    /** OI 窗口内变化百分比。 */
    public static BigDecimal oiChangePct(List<OiPoint> history) {
        if (history == null || history.size() < 2) {
            return null;
        }
        BigDecimal first = history.get(0).oi();
        BigDecimal last = history.get(history.size() - 1).oi();
        return pctChange(first, last);
    }

    /** 盘口不平衡度：前 N 档买量 / 卖量。>1 买盘厚，<1 卖盘厚。 */
    public static BigDecimal orderbookImbalance(OrderBook book, int levels) {
        if (book == null) {
            return null;
        }
        BigDecimal bidQty = book.bids().stream().limit(levels)
                .map(OrderBook.Level::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal askQty = book.asks().stream().limit(levels)
                .map(OrderBook.Level::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (askQty.signum() == 0) {
            return null;
        }
        return bidQty.divide(askQty, 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal spread(OrderBook book) {
        if (book == null || book.bids().isEmpty() || book.asks().isEmpty()) {
            return null;
        }
        return book.asks().get(0).price().subtract(book.bids().get(0).price());
    }

    /** 成交统计：主动买占比。 */
    public record TradesSummary(int count, int buyCount, BigDecimal buyVolumeRatio) {
    }

    public static TradesSummary tradesSummary(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return null;
        }
        int buyCount = 0;
        BigDecimal buyVol = BigDecimal.ZERO;
        BigDecimal totalVol = BigDecimal.ZERO;
        for (Trade trade : trades) {
            totalVol = totalVol.add(trade.qty());
            if (trade.buyAggressor()) {
                buyCount++;
                buyVol = buyVol.add(trade.qty());
            }
        }
        BigDecimal ratio = totalVol.signum() == 0 ? null
                : buyVol.divide(totalVol, 4, RoundingMode.HALF_UP);
        return new TradesSummary(trades.size(), buyCount, ratio);
    }

    /**
     * 两所价格变化差异（异常差异检测）。
     * diff = |changeA - changeB|，超过阈值视为显著背离。
     */
    public record Divergence(BigDecimal changePctA, BigDecimal changePctB,
                             BigDecimal diffPct, boolean significant) {
    }

    public static Divergence divergence(PriceChange a, PriceChange b, BigDecimal thresholdPct) {
        if (a == null || b == null) {
            return null;
        }
        BigDecimal diff = a.changePct().subtract(b.changePct()).abs();
        return new Divergence(a.changePct(), b.changePct(), diff, diff.compareTo(thresholdPct) > 0);
    }

    private static BigDecimal pctChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return null;
        }
        return to.subtract(from)
                .divide(from, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
    }
}
