package com.cexpilot.metric;

import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Provider 的标准结果；领域数据始终保持 Java 类型，只有输出边界才转 JSON。 */
public sealed interface MetricResult {
    Metadata metadata();

    record Scalar(Metadata metadata, BigDecimal value, BigDecimal observationSeconds,
                  TimeRange actualRange) implements MetricResult {
        public Scalar {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(observationSeconds, "observationSeconds");
            Objects.requireNonNull(actualRange, "actualRange");
        }
    }
    /** 成功取数但未覆盖完整区间，不产生统计值。 */
    record Omitted(Metadata metadata, String reason) implements MetricResult {}
    record Series(Metadata metadata, List<Sample> samples) implements MetricResult {
        public Series { samples = List.copyOf(samples); }
    }
    record Sample(Instant time, BigDecimal value) {
        public Sample { Objects.requireNonNull(time); Objects.requireNonNull(value); }
    }
    record Metadata(TimeRange requestedRange, TimeRange effectiveRange, SeriesCoverage coverage,
                    CandleInterval interval, boolean automaticInterval, int candleCount) {
        public Metadata {
            Objects.requireNonNull(requestedRange); Objects.requireNonNull(effectiveRange);
            Objects.requireNonNull(coverage); Objects.requireNonNull(interval);
        }
    }
}
