package com.cexpilot.market.series;

import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;

import java.util.Arrays;
import java.util.Comparator;

/** K 线序列和区间统计共用的缺省粒度策略；用户显式指定时不替换。 */
public final class CandleIntervalSelector {

    // 查询开销目标，不是硬上限；长区间仍可用最粗粒度在数据源总预算内分页。
    private static final int TARGET_POINTS = 1500;

    private CandleIntervalSelector() {
    }

    public static CandleInterval select(CandleInterval requested, TimeRange range, SeriesCapability capability) {
        if (requested != null) {
            return requested;
        }
        var candidates = Arrays.stream(CandleInterval.values())
                .filter(interval -> capability.supportedIntervals().isEmpty()
                        || capability.supportedIntervals().contains(interval))
                .sorted(Comparator.comparing(CandleInterval::duration))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("数据源没有可用的 K 线粒度");
        }
        long target = Math.min(TARGET_POINTS, capability.budget());
        for (CandleInterval interval : candidates) {
            TimeRange aligned = SeriesQueryPolicy.align(interval, range);
            long count = (aligned.endExclusive().toEpochMilli() - aligned.startInclusive().toEpochMilli())
                    / interval.duration().toMillis();
            if (count <= target) {
                return interval;
            }
        }
        // 最粗粒度也超过目标时，由现有 policy 判断总预算、保留期等，不绕过能力校验。
        return candidates.get(candidates.size() - 1);
    }
}
