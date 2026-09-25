package com.cexpilot.market.series;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 历史序列覆盖核对（查询后检查「实际是否满足」）：不发 HTTP 请求，也不猜测用户想查什么。
 * 1. 根据起止时间和粒度生成预期的时间点序列；
 * 2. 对照实际返回检查缺失、错位、重复；
 * 3. 要求已完结数据时，剔除并标注尚未完结的点。
 * 完结状态以数据源标记（如 OKX confirm）优先，缺失时按点语义推断。
 * 返回数量只用于快速发现异常，完整性以时间位置比对为准。
 */
public final class SeriesCoverageValidator {

    /**
     * 数据点语义：
     * PERIOD——点是周期的起点，到 t+interval 才完结（K 线、资金费率结算期）；
     * INSTANT——点是 t 时刻的瞬时值，过了 t 即确定（持仓量采样）。
     */
    public enum PointSemantics {
        PERIOD, INSTANT
    }

    private SeriesCoverageValidator() {
    }

    /**
     * PERIOD 语义、区间终点即 beyond-now 判断终点的便捷入口（K 线场景）。
     */
    public static SeriesValidation validate(long intervalMs, long startMs, long endMs,
                                            boolean includeUnclosed, List<TimePoint> fetched,
                                            String abortReason, Instant requestTime) {
        return validate(PointSemantics.PERIOD, intervalMs, startMs, endMs, includeUnclosed,
                fetched, abortReason, requestTime, endMs);
    }

    /**
     * @param semantics       点语义（PERIOD / INSTANT），决定未完结推断与 coveredUntil 的算法
     * @param intervalMs      粒度（毫秒）
     * @param startMs         区间起点（含，ms）
     * @param endMs           区间终点（不含，ms）
     * @param includeUnclosed 是否允许包含未完结点
     * @param fetched         数据源返回的数据点（ms 均在区间内）
     * @param abortReason     数据源分页中止原因，null 表示正常结束
     * @param requestTime     判定未完结的基准时间（请求开始时固定的 requestTime）
     * @param beyondNowEndMs  「请求区间是否延伸到基准时间之后」的判断终点；
     *                        通常等于 endMs，funding 平移校验区间时传未平移的原始终点
     */
    public static SeriesValidation validate(PointSemantics semantics, long intervalMs, long startMs, long endMs,
                                            boolean includeUnclosed, List<TimePoint> fetched,
                                            String abortReason, Instant requestTime, long beyondNowEndMs) {
        long nowMs = requestTime.toEpochMilli();

        // 预期时间点序列：以 intervalMs 网格锚定（startMs 未对齐时从下一个网格点开始），
        // 调用方保证数据点也落在同一网格上；未完结点在要求已完结时从预期中剔除
        Set<Long> expected = new LinkedHashSet<>();
        Set<Long> dropped = new LinkedHashSet<>();
        long firstGrid = startMs % intervalMs == 0 ? startMs : (startMs / intervalMs + 1) * intervalMs;
        for (long t = firstGrid; t < endMs; t += intervalMs) {
            if (!includeUnclosed && isUnclosed(semantics, t, intervalMs, nowMs)) {
                dropped.add(t);
                continue;
            }
            expected.add(t);
        }

        Set<Long> seen = new LinkedHashSet<>();
        List<Long> duplicates = new ArrayList<>();
        List<Long> unexpected = new ArrayList<>();
        List<TimePoint> accepted = new ArrayList<>();
        boolean containsUnclosed = false;
        boolean droppedBySourceFlag = false;
        for (TimePoint point : fetched) {
            if (!seen.add(point.ms())) {
                duplicates.add(point.ms());
                continue;
            }
            if (dropped.contains(point.ms())) {
                continue; // 按要求剔除的未完结点：不算错位，也不纳入结果
            }
            if (!expected.contains(point.ms())) {
                unexpected.add(point.ms());
                continue;
            }
            // 完结状态：数据源标记（OKX confirm）优先；缺失时按点语义推断。
            // 请求跨过结束时刻时，取数时的部分数据不会因为校验时已到结束时刻而变成最终数据
            boolean unclosed = point.confirmed() != null
                    ? !point.confirmed()
                    : isUnclosed(semantics, point.ms(), intervalMs, nowMs);
            if (unclosed && !includeUnclosed) {
                droppedBySourceFlag = true; // 按未完结剔除；该点随后进入 missing
                continue;
            }
            if (unclosed) {
                containsUnclosed = true;
            }
            accepted.add(point);
        }

        List<Long> missing = new ArrayList<>();
        Set<Long> acceptedTimes = new LinkedHashSet<>();
        for (TimePoint point : accepted) {
            acceptedTimes.add(point.ms());
        }
        for (long t : expected) {
            if (!acceptedTimes.contains(t)) {
                missing.add(t);
            }
        }

        Long coveredUntilMs = null;
        for (TimePoint point : accepted) {
            long until = semantics == PointSemantics.INSTANT ? point.ms() : point.ms() + intervalMs;
            coveredUntilMs = coveredUntilMs == null ? until : Math.max(coveredUntilMs, until);
        }

        SeriesCoverage coverage = new SeriesCoverage(expected.size(), accepted.size(),
                List.copyOf(missing), List.copyOf(unexpected), List.copyOf(duplicates),
                containsUnclosed, !dropped.isEmpty() || droppedBySourceFlag, abortReason,
                coveredUntilMs, beyondNowEndMs > nowMs);
        return new SeriesValidation(accepted, coverage);
    }

    private static boolean isUnclosed(PointSemantics semantics, long t, long intervalMs, long nowMs) {
        return semantics == PointSemantics.INSTANT ? t > nowMs : t + intervalMs > nowMs;
    }
}
