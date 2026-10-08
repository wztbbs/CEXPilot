package com.cexpilot.metric;

import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
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
    /** 快照形态的单点值：没有观测窗口，也不做覆盖核对。 */
    record Point(Metadata metadata, BigDecimal value) implements MetricResult {
        public Point {
            Objects.requireNonNull(value, "value");
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
    /**
     * 形态共有的信息；快照形态的区间字段为 null，区间形态忽略 asOf。
     * requestedRange / effectiveRange 在 SNAPSHOT 下返回 null，输出层据此省略窗口字段。
     */
    interface Metadata {
        TimeRange requestedRange();
        TimeRange effectiveRange();
    }
    /** 序列形态的元数据：覆盖核对、粒度（对外 code 编码，由 Provider 自解析）与采样数。 */
    record SeriesMetadata(TimeRange requestedRange, TimeRange effectiveRange, SeriesCoverage coverage,
                          String interval, boolean automaticInterval, int candleCount) implements Metadata {
        public SeriesMetadata {
            Objects.requireNonNull(requestedRange); Objects.requireNonNull(effectiveRange);
            Objects.requireNonNull(coverage); Objects.requireNonNull(interval);
        }
    }
    /** 快照形态的元数据：只有数据时间与渲染时区，没有区间与覆盖。 */
    record SnapshotMetadata(Instant asOf, ZoneId zone) implements Metadata {
        public SnapshotMetadata {
            Objects.requireNonNull(asOf, "asOf"); Objects.requireNonNull(zone, "zone");
        }
        public TimeRange requestedRange() { return null; }
        public TimeRange effectiveRange() { return null; }
    }
    /**
     * 最近 N 期形态的元数据：以期数取样，不做覆盖核对；样本不足时 sampleComplete=false，
     * 由算子在引用前拦截。periodMs 是该指标的周期（如结算周期），4b 年化再开放为可引用字段。
     */
    record RecentMetadata(int requestedCount, int actualCount, boolean sampleComplete, long periodMs, ZoneId zone)
            implements Metadata {
        public RecentMetadata {
            Objects.requireNonNull(zone, "zone");
        }
        public TimeRange requestedRange() { return null; }
        public TimeRange effectiveRange() { return null; }
    }
}
