package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.TakerVolumePoint;
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

    /** 区间统计：开高低收、涨跌幅、成交量与成交额合计。 */
    public record RangeStats(BigDecimal open, BigDecimal close, BigDecimal high, BigDecimal low,
                             BigDecimal changePct, BigDecimal volume, BigDecimal quoteVolume,
                             int candleCount) {
    }

    /**
     * 基于通过覆盖核对的 K 线计算区间统计；调用方负责保证序列完整（缺失时不得调用），
     * 空列表返回 null。quoteVolume 任一根缺失时合计为 null（不输出无法证明的成交额）。
     */
    public static RangeStats rangeStats(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        BigDecimal open = candles.get(0).open();
        BigDecimal close = candles.get(candles.size() - 1).close();
        BigDecimal high = candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(close);
        BigDecimal low = candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(close);
        BigDecimal volume = candles.stream().map(Candle::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal quoteVolume = null;
        if (candles.stream().allMatch(c -> c.quoteVolume() != null)) {
            quoteVolume = candles.stream().map(Candle::quoteVolume).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return new RangeStats(open, close, high, low, pctChange(open, close),
                volume, quoteVolume, candles.size());
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

    /**
     * 区间内已结算费率统计。
     *
     * @param mean          均值（小数形式）
     * @param trend         复用 fundingTrend：后半段均值 vs 前半段均值，不足 4 期为 unknown
     * @param periodCount   期数
     */
    public record FundingStats(BigDecimal mean, BigDecimal min, BigDecimal max,
                               int positiveCount, int negativeCount, int zeroCount,
                               String trend, int periodCount) {
    }

    /** 调用方负责保证序列为通过覆盖核对的完整已结算序列；空列表返回 null。 */
    public static FundingStats fundingStats(List<BigDecimal> rates) {
        if (rates == null || rates.isEmpty()) {
            return null;
        }
        BigDecimal min = rates.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = rates.stream().max(BigDecimal::compareTo).orElseThrow();
        int positive = 0;
        int negative = 0;
        int zero = 0;
        for (BigDecimal rate : rates) {
            if (rate.signum() > 0) {
                positive++;
            } else if (rate.signum() < 0) {
                negative++;
            } else {
                zero++;
            }
        }
        return new FundingStats(avg(rates), min, max, positive, negative, zero,
                fundingTrend(rates), rates.size());
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

    /**
     * 持仓量区间统计。
     *
     * @param changePct 期初 → 期末变化率（%）；期初为 0 时为 null
     * @param maxTime   最高采样值对应的采样时间戳（毫秒）
     * @param minTime   最低采样值对应的采样时间戳（毫秒）
     */
    public record OiStats(BigDecimal startOi, BigDecimal endOi, BigDecimal change, BigDecimal changePct,
                          BigDecimal maxOi, long maxTime, BigDecimal minOi, long minTime, int pointCount) {
    }

    /** 调用方负责保证序列为通过覆盖核对的完整采样序列；空列表返回 null。 */
    public static OiStats oiStats(List<OiPoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        BigDecimal start = points.get(0).oi();
        BigDecimal end = points.get(points.size() - 1).oi();
        OiPoint max = points.get(0);
        OiPoint min = points.get(0);
        for (OiPoint point : points) {
            if (point.oi().compareTo(max.oi()) > 0) {
                max = point;
            }
            if (point.oi().compareTo(min.oi()) < 0) {
                min = point;
            }
        }
        return new OiStats(start, end, end.subtract(start), pctChange(start, end),
                max.oi(), max.timestamp(), min.oi(), min.timestamp(), points.size());
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

    /** 数值精度规整：HALF_UP 保留 scale 位小数，杀掉上游 API 带来的浮点噪声。 */
    public static BigDecimal round(BigDecimal value, int scale) {
        if (value == null) {
            return null;
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * round 之后去掉多余的尾零，并保证 toString 为 plain 表示——Jackson 默认按
     * BigDecimal.toString() 序列化，stripTrailingZeros 直接输出会出现 1E-4 这类
     * 科学计数，混进 prompt 干扰模型读数。
     */
    public static BigDecimal roundPlain(BigDecimal value, int scale) {
        BigDecimal rounded = round(value, scale);
        return rounded == null ? null : new BigDecimal(rounded.stripTrailingZeros().toPlainString());
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

    /**
     * 区间内 taker 主动买卖流量统计（交易所官方 5m 统计序列求和）。
     *
     * @param buyVolumeRatio 主动买成交量占总量比例（0~1，4 位小数）；总量为 0 时为 null
     */
    public record TakerFlowStats(BigDecimal buyVolume, BigDecimal sellVolume,
                                 BigDecimal buyVolumeRatio, int pointCount) {
    }

    /** 调用方负责保证 5m 序列为通过覆盖核对的完整序列（分页被中止时不得调用）；空列表返回 null。 */
    public static TakerFlowStats takerFlowStats(List<TakerVolumePoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        BigDecimal buyVolume = BigDecimal.ZERO;
        BigDecimal sellVolume = BigDecimal.ZERO;
        for (TakerVolumePoint point : points) {
            buyVolume = buyVolume.add(point.buyVolume());
            sellVolume = sellVolume.add(point.sellVolume());
        }
        BigDecimal totalVolume = buyVolume.add(sellVolume);
        BigDecimal ratio = totalVolume.signum() == 0 ? null
                : buyVolume.divide(totalVolume, 4, RoundingMode.HALF_UP);
        return new TakerFlowStats(buyVolume, sellVolume, ratio, points.size());
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
    }
}
