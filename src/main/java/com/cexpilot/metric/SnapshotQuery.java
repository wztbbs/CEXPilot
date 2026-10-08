package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import java.util.Objects;

/** 快照形态的指标查询：当前时刻取值，没有时间窗口、粒度与未完结数据开关；depth 仅盘口使用。 */
public record SnapshotQuery(MetricBinding binding, Integer depth) implements MetricQuery {
    public SnapshotQuery(MetricBinding binding) {
        this(binding, null);
    }

    public SnapshotQuery {
        Objects.requireNonNull(binding, "binding");
        InstrumentRules.requirePerpetualUsdt(binding);
        if (depth != null && (depth < 5 || depth > 50)) {
            throw new IllegalArgumentException("depth 必须在 5~50 之间: " + depth);
        }
    }
    public Exchange exchange() { return InstrumentRules.exchange(binding); }
    public String base() { return InstrumentRules.base(binding); }
}
