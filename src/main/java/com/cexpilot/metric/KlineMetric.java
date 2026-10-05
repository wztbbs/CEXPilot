package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator.RangeStats;
import com.cexpilot.market.model.Candle;
import java.math.BigDecimal;
import java.util.function.Function;

/** 目录中的稳定选择器，直接读取领域模型，不依赖 Tool JSON 字段名。 */
public enum KlineMetric {
    OPEN(Candle::open, RangeStats::open),
    CLOSE(Candle::close, RangeStats::close),
    HIGH(Candle::high, RangeStats::high),
    LOW(Candle::low, RangeStats::low),
    CHANGE_PCT(null, RangeStats::changePct),
    VOLUME(Candle::volume, RangeStats::volume),
    TURNOVER(null, RangeStats::quoteVolume);

    private final Function<Candle, BigDecimal> sample;
    private final Function<RangeStats, BigDecimal> statistic;

    KlineMetric(Function<Candle, BigDecimal> sample, Function<RangeStats, BigDecimal> statistic) {
        this.sample = sample;
        this.statistic = statistic;
    }

    public BigDecimal sample(Candle candle) {
        if (sample == null) throw new IllegalArgumentException("指标不支持 time_series: " + this);
        return required(sample.apply(candle));
    }

    public BigDecimal statistic(RangeStats stats) { return required(statistic.apply(stats)); }
    public boolean supports(String shape) {
        return "range_statistic".equals(shape) || "time_series".equals(shape) && sample != null;
    }
    private static BigDecimal required(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("来源指标不是数值或缺失");
        return value;
    }
}
