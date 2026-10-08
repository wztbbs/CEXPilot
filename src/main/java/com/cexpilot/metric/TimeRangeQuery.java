package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.time.TimeSpec;
import java.util.Objects;

/** 时间区间形态的指标查询；interval 以原始编码传递，由 Provider 自行解析。 */
public record TimeRangeQuery(MetricBinding binding, TimeSpec time, String intervalCode, boolean includeUnclosed)
        implements MetricQuery {
    public TimeRangeQuery {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(time, "time");
        InstrumentRules.requirePerpetualUsdt(binding);
    }
    public Exchange exchange() { return InstrumentRules.exchange(binding); }
    public String base() { return InstrumentRules.base(binding); }
}
