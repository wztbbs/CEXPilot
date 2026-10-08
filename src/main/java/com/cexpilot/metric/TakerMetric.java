package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator.TakerFlowStats;
import java.math.BigDecimal;
import java.util.function.Function;

/** taker 成交流量指标选择器：交易所官方 5m 统计序列求和，仅区间统计形态。 */
public enum TakerMetric implements MetricSelector {
    BUY_VOLUME(TakerFlowStats::buyVolume),
    SELL_VOLUME(TakerFlowStats::sellVolume),
    BUY_RATIO(TakerFlowStats::buyVolumeRatio);

    private final Function<TakerFlowStats, BigDecimal> statistic;

    TakerMetric(Function<TakerFlowStats, BigDecimal> statistic) {
        this.statistic = statistic;
    }

    public BigDecimal statistic(TakerFlowStats stats) { return required(statistic.apply(stats)); }

    @Override public boolean supports(String shape) {
        return QueryShape.RANGE_STATISTIC.code().equals(shape);
    }

    private static BigDecimal required(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("来源指标不是数值或缺失");
        return value;
    }
}
