package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import java.util.Objects;

/** 最近 N 期形态的指标查询：按期数取样，样本语义不承诺时间跨度。 */
public record CountQuery(MetricBinding binding, int count) implements MetricQuery {
    public CountQuery {
        Objects.requireNonNull(binding, "binding");
        InstrumentRules.requirePerpetualUsdt(binding);
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("count 必须在 1~100 之间: " + count);
        }
    }
    public Exchange exchange() { return InstrumentRules.exchange(binding); }
    public String base() { return InstrumentRules.base(binding); }
}
