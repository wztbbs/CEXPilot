package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator.OiStats;
import com.cexpilot.market.model.OiPoint;
import java.math.BigDecimal;
import java.util.function.Function;

/** 持仓量指标选择器：QUANTITY 同时支持序列与快照，其余为区间统计；极值时间字符串不进 value。 */
public enum OiMetric implements MetricSelector {
    QUANTITY(OiPoint::oi, null, true),
    START(null, OiStats::startOi, false),
    END(null, OiStats::endOi, false),
    CHANGE(null, OiStats::change, false),
    CHANGE_PCT(null, OiStats::changePct, false),
    MIN(null, OiStats::minOi, false),
    MAX(null, OiStats::maxOi, false);

    private final Function<OiPoint, BigDecimal> sample;
    private final Function<OiStats, BigDecimal> statistic;
    private final boolean snapshot;

    OiMetric(Function<OiPoint, BigDecimal> sample, Function<OiStats, BigDecimal> statistic, boolean snapshot) {
        this.sample = sample;
        this.statistic = statistic;
        this.snapshot = snapshot;
    }

    public BigDecimal sample(OiPoint point) {
        if (sample == null) throw new IllegalArgumentException("指标不支持 time_series: " + this);
        return required(sample.apply(point));
    }

    public BigDecimal statistic(OiStats stats) {
        if (statistic == null) throw new IllegalArgumentException("指标不支持 range_statistic: " + this);
        return required(statistic.apply(stats));
    }

    public boolean supportsSnapshot() { return snapshot; }

    @Override public boolean supports(String shape) {
        return QueryShape.TIME_SERIES.code().equals(shape) && sample != null
                || QueryShape.RANGE_STATISTIC.code().equals(shape) && statistic != null
                || QueryShape.SNAPSHOT.code().equals(shape) && snapshot;
    }

    private static BigDecimal required(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("来源指标不是数值或缺失");
        return value;
    }
}
