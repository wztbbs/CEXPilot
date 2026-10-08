package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator.PriceChange;
import com.cexpilot.market.markprice.PriceType;
import com.cexpilot.market.model.Candle;
import java.math.BigDecimal;
import java.util.function.Function;

/** 标记/指数价格指标选择器：priceType 是绑定注入的常量参数，计算路径与成交价 K 线同源。 */
public enum MarkPriceMetric implements MetricSelector {
    MARK_OPEN(PriceType.MARK, Candle::open, PriceChange::startPrice),
    MARK_CLOSE(PriceType.MARK, Candle::close, PriceChange::endPrice),
    MARK_HIGH(PriceType.MARK, Candle::high, PriceChange::high),
    MARK_LOW(PriceType.MARK, Candle::low, PriceChange::low),
    MARK_CHANGE_PCT(PriceType.MARK, null, PriceChange::changePct),
    INDEX_OPEN(PriceType.INDEX, Candle::open, PriceChange::startPrice),
    INDEX_CLOSE(PriceType.INDEX, Candle::close, PriceChange::endPrice),
    INDEX_HIGH(PriceType.INDEX, Candle::high, PriceChange::high),
    INDEX_LOW(PriceType.INDEX, Candle::low, PriceChange::low),
    INDEX_CHANGE_PCT(PriceType.INDEX, null, PriceChange::changePct);

    private final PriceType priceType;
    private final Function<Candle, BigDecimal> sample;
    private final Function<PriceChange, BigDecimal> statistic;

    MarkPriceMetric(PriceType priceType, Function<Candle, BigDecimal> sample,
                    Function<PriceChange, BigDecimal> statistic) {
        this.priceType = priceType;
        this.sample = sample;
        this.statistic = statistic;
    }

    public PriceType priceType() { return priceType; }

    public BigDecimal sample(Candle candle) {
        if (sample == null) throw new IllegalArgumentException("指标不支持 time_series: " + this);
        return required(sample.apply(candle));
    }

    public BigDecimal statistic(PriceChange change) { return required(statistic.apply(change)); }

    @Override public boolean supports(String shape) {
        return QueryShape.RANGE_STATISTIC.code().equals(shape)
                || QueryShape.TIME_SERIES.code().equals(shape) && sample != null;
    }

    private static BigDecimal required(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("来源指标不是数值或缺失");
        return value;
    }
}
