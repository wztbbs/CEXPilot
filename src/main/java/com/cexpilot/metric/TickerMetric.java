package com.cexpilot.metric;

import com.cexpilot.market.model.Ticker;
import java.math.BigDecimal;
import java.util.function.Function;

/**
 * 24h ticker 上的指标选择器。
 * LAST 属于 snapshot（当前时刻最新成交价）；其余属于 official_24h（交易所官方滚动 24 小时口径），
 * 两种形态的窗口定义不同，不能互相替换。
 */
public enum TickerMetric implements MetricSelector {
    LAST(Ticker::lastPrice, QueryShape.SNAPSHOT),
    CHANGE_PCT_24H(Ticker::changePct24h, QueryShape.OFFICIAL_24H),
    VOLUME_24H(Ticker::baseVolume24h, QueryShape.OFFICIAL_24H),
    TURNOVER_24H(Ticker::quoteVolume24h, QueryShape.OFFICIAL_24H);

    private final Function<Ticker, BigDecimal> snapshot;
    private final QueryShape shape;

    TickerMetric(Function<Ticker, BigDecimal> snapshot, QueryShape shape) {
        this.snapshot = snapshot;
        this.shape = shape;
    }

    /** 允许返回 null：交易所未提供时由 Provider 输出 Omitted 并说明原因，不猜零值。 */
    public BigDecimal snapshot(Ticker ticker) { return snapshot.apply(ticker); }

    /** 成交额必须来自逐笔汇总；估算值不能冒充精确成交额。 */
    public boolean rejectsEstimated() { return this == TURNOVER_24H; }

    public QueryShape shape() { return shape; }

    @Override public boolean supports(String shape) { return this.shape.code().equals(shape); }
}
