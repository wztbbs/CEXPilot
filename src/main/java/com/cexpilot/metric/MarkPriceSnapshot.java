package com.cexpilot.metric;

import com.cexpilot.market.model.MarkPrice;
import java.math.BigDecimal;
import java.util.function.Function;

/** 标记价/指数价的快照选择器：值与其来源时间分开取，时间不一致时不能合成派生指标。 */
public enum MarkPriceSnapshot implements MetricSelector {
    MARK(MarkPrice::markPrice, MarkPrice::markPriceTime),
    INDEX(MarkPrice::indexPrice, MarkPrice::indexPriceTime);

    private final Function<MarkPrice, BigDecimal> value;
    private final java.util.function.ToLongFunction<MarkPrice> time;

    MarkPriceSnapshot(Function<MarkPrice, BigDecimal> value, java.util.function.ToLongFunction<MarkPrice> time) {
        this.value = value;
        this.time = time;
    }

    public BigDecimal value(MarkPrice price) { return value.apply(price); }
    public long time(MarkPrice price) { return time.applyAsLong(price); }

    @Override public boolean supports(String shape) { return QueryShape.SNAPSHOT.code().equals(shape); }
}
