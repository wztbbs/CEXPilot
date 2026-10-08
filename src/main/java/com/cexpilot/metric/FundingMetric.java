package com.cexpilot.metric;

import com.cexpilot.market.model.FundingRatePoint;
import java.math.BigDecimal;
import java.util.function.Function;

/** 已结算资金费率选择器：只接 recent_n，按结算期取样，不做区间统计。 */
public enum FundingMetric implements MetricSelector {
    RATE(FundingRatePoint::rate);

    private final Function<FundingRatePoint, BigDecimal> sample;

    FundingMetric(Function<FundingRatePoint, BigDecimal> sample) { this.sample = sample; }

    public BigDecimal sample(FundingRatePoint point) { return required(sample.apply(point)); }

    @Override public boolean supports(String shape) { return QueryShape.RECENT_N.code().equals(shape); }

    private static BigDecimal required(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("来源指标不是数值或缺失");
        return value;
    }
}
