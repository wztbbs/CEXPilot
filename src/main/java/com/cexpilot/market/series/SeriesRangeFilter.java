package com.cexpilot.market.series;

import com.cexpilot.time.TimeRange;

import java.util.List;
import java.util.function.ToLongFunction;

/** 按已统一的时间戳口径裁回计算范围；不去重、不对齐，区间内异常留给覆盖校验器。 */
public final class SeriesRangeFilter {

    private SeriesRangeFilter() {
    }

    public static <T> List<T> withinRange(List<T> points, TimeRange range, ToLongFunction<T> timestamp) {
        long start = range.startInclusive().toEpochMilli();
        long end = range.endExclusive().toEpochMilli();
        return points.stream().filter(point -> {
            long time = timestamp.applyAsLong(point);
            return time >= start && time < end;
        }).toList();
    }
}
