package com.cexpilot.market.series;

import com.cexpilot.time.SeriesInterval;
import com.cexpilot.time.TimeRange;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * 历史序列查询前检查「这个请求是否能满足」，依据数据源提供的能力信息，
 * 调用方不自行维护另一份交易所能力表。
 * 通过后返回对齐后的区间：区间未对齐粒度边界时被外扩到粒度边界。
 */
public final class SeriesQueryPolicy {

    private SeriesQueryPolicy() {
    }

    /**
     * @return 对齐后的查询区间（已对齐时与入参相同）
     */
    public static TimeRange check(SeriesInterval interval, TimeRange range,
                                  SeriesCapability capability, String exchangeDisplayName,
                                  Instant requestTime) {
        if (!capability.supportedIntervals().isEmpty()
                && !capability.supportedIntervals().contains(interval)) {
            String supported = capability.supportedIntervals().stream()
                    .map(SeriesInterval::code).sorted().collect(Collectors.joining(" / "));
            throw new IllegalArgumentException(
                    exchangeDisplayName + " 不支持粒度 " + interval.code() + "，支持: " + supported);
        }
        if (capability.retentionDays() != null
                && range.startInclusive().toEpochMilli()
                        < requestTime.toEpochMilli() - capability.retentionDays() * 86_400_000L) {
            throw new IllegalArgumentException(
                    exchangeDisplayName + " 只保留最近 " + capability.retentionDays()
                            + " 天的历史数据，区间起点 " + range.startInclusive() + " 超出可查范围；"
                            + "请缩短回溯范围或换用支持更长历史的交易所");
        }

        long intervalMs = interval.duration().toMillis();
        long startMs = range.startInclusive().toEpochMilli();
        long endMs = range.endExclusive().toEpochMilli();

        TimeRange effective = range;
        long alignedStart = Math.floorDiv(startMs, intervalMs) * intervalMs;
        long alignedEnd = Math.floorDiv(endMs + intervalMs - 1, intervalMs) * intervalMs;
        if (alignedStart != startMs || alignedEnd != endMs) {
            effective = new TimeRange(Instant.ofEpochMilli(alignedStart),
                    Instant.ofEpochMilli(alignedEnd), range.timezone());
        }

        long pointsNeeded = (effective.endExclusive().toEpochMilli()
                - effective.startInclusive().toEpochMilli()) / intervalMs;
        if (pointsNeeded > capability.budget()) {
            throw new IllegalArgumentException(
                    "区间过长：按 " + interval.code() + " 粒度需要 " + pointsNeeded
                            + " 个数据点，超过单次查询预算 " + capability.budget()
                            + " 个；请缩短区间或换更粗粒度");
        }
        return effective;
    }
}
