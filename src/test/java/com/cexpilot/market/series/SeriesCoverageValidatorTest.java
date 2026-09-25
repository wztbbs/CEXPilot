package com.cexpilot.market.series;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesCoverageValidatorTest {

    private static final long FIVE_MIN_MS = 5 * 60_000L;
    private static final long START = 1_700_000_100_000L; // 网格对齐起点（300000 的整数倍）
    private static final int COUNT = 12;
    private static final long END = START + COUNT * FIVE_MIN_MS;

    private static List<TimePoint> fullSequence() {
        List<TimePoint> points = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            points.add(new TimePoint(START + i * FIVE_MIN_MS));
        }
        return points;
    }

    private static SeriesValidation validate(List<TimePoint> fetched, boolean includeUnclosed,
                                             String abortReason, long nowMs) {
        return SeriesCoverageValidator.validate(FIVE_MIN_MS, START, END, includeUnclosed,
                fetched, abortReason, Instant.ofEpochMilli(nowMs));
    }

    @Test
    void completeWhenAllExpectedPresent() {
        // requestTime=END：最后一个点刚好完结
        SeriesValidation v = validate(fullSequence(), false, null, END);
        assertTrue(v.coverage().complete());
        assertTrue(v.coverage().rangeComplete());
        assertEquals(COUNT, v.coverage().expectedCount());
        assertEquals(COUNT, v.coverage().actualCount());
        assertEquals(COUNT, v.points().size());
        assertEquals(END, (long) v.coverage().coveredUntilMs());
    }

    @Test
    void missingDetectedByTimePosition() {
        List<TimePoint> points = fullSequence();
        points.remove(5); // 数量少 1 个只是表象，missing 必须给出具体时间位置
        SeriesValidation v = validate(points, false, null, END);
        assertFalse(v.coverage().complete());
        assertEquals(List.of(START + 5 * FIVE_MIN_MS), v.coverage().missing());
        assertEquals(COUNT - 1, v.points().size());
    }

    @Test
    void misalignedPointRejected() {
        List<TimePoint> points = fullSequence();
        points.add(new TimePoint(START + 2 * 60_000L)); // 错位：不在粒度边界上
        SeriesValidation v = validate(points, false, null, END);
        assertFalse(v.coverage().complete());
        assertEquals(List.of(START + 2 * 60_000L), v.coverage().unexpected());
        assertEquals(COUNT, v.points().size()); // 错位点不进入结果
    }

    @Test
    void duplicatePointFlagged() {
        List<TimePoint> points = fullSequence();
        points.add(new TimePoint(START + FIVE_MIN_MS));
        SeriesValidation v = validate(points, false, null, END);
        assertFalse(v.coverage().complete());
        assertEquals(List.of(START + FIVE_MIN_MS), v.coverage().duplicates());
    }

    @Test
    void unclosedDroppedWhenClosedRequired() {
        // requestTime 落在最后一个点的周期内：该点未完结
        SeriesValidation v = validate(fullSequence(), false, null, END - 60_000L);
        assertTrue(v.coverage().droppedUnclosed());
        assertFalse(v.coverage().containsUnclosed());
        assertEquals(COUNT - 1, v.coverage().expectedCount());
        assertEquals(COUNT - 1, v.points().size());
        // 被剔除的未完结点不算错位，不影响已完结部分完整性
        assertTrue(v.coverage().complete());
        assertFalse(v.coverage().rangeComplete()); // 但请求区间越过 requestTime，整体是部分覆盖
    }

    @Test
    void unclosedKeptAndFlaggedWhenIncluded() {
        SeriesValidation v = validate(fullSequence(), true, null, END - 60_000L);
        assertTrue(v.coverage().containsUnclosed());
        assertFalse(v.coverage().droppedUnclosed());
        assertEquals(COUNT, v.points().size());
        assertTrue(v.coverage().complete());
    }

    @Test
    void sourceConfirmFlagOverridesTimeInference() {
        // 取数时未完结（confirmed=false），校验时刻已过结束时刻——不能当成最终数据
        List<TimePoint> points = fullSequence();
        points.set(3, new TimePoint(START + 3 * FIVE_MIN_MS, Boolean.FALSE));
        SeriesValidation v = validate(points, false, null, END);
        assertFalse(v.coverage().complete());
        assertEquals(List.of(START + 3 * FIVE_MIN_MS), v.coverage().missing());
        assertTrue(v.coverage().droppedUnclosed());
        assertEquals(COUNT - 1, v.points().size());

        // confirmed=true 且时间推断未完结时，信任数据源标记（requestTime 时有 2 个按时间未完结）
        List<TimePoint> confirmed = fullSequence();
        for (int i : new int[]{COUNT - 2, COUNT - 1}) {
            confirmed.set(i, new TimePoint(START + i * FIVE_MIN_MS, Boolean.TRUE));
        }
        SeriesValidation trusted = validate(confirmed, true, null, END - 60_000L);
        assertFalse(trusted.coverage().containsUnclosed());
        assertEquals(COUNT, trusted.points().size());
    }

    @Test
    void abortReasonMakesIncomplete() {
        SeriesValidation v = validate(fullSequence(), false, "分页请求预算用尽", END);
        assertFalse(v.coverage().complete());
        assertEquals("分页请求预算用尽", v.coverage().abortReason());
    }

    @Test
    void instantSemanticsKeepCurrentSample() {
        // INSTANT：点是时刻值，12:00 的采样在 12:03 已确定；未来采样仍剔除，coveredUntil=最后采样时刻
        List<TimePoint> points = fullSequence();
        long now = END - 60_000L; // 最后一个点在 now 之前 1 分钟
        SeriesValidation v = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.INSTANT,
                FIVE_MIN_MS, START, END, false, points, null,
                Instant.ofEpochMilli(now), END);
        assertEquals(COUNT, v.coverage().expectedCount());
        assertEquals(END - FIVE_MIN_MS, (long) v.coverage().coveredUntilMs());
        assertTrue(v.coverage().complete());
        // 同一时刻按 PERIOD 语义则最后一个点未完结被剔除
        SeriesValidation period = validate(points, false, null, now);
        assertEquals(COUNT - 1, period.coverage().expectedCount());
        assertTrue(period.coverage().droppedUnclosed());
        // INSTANT + 未来采样：t > now 的被剔除（now=START+31min，保留 00~30min 共 7 个）
        long earlier = START + 6 * FIVE_MIN_MS + 60_000L;
        SeriesValidation future = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.INSTANT,
                FIVE_MIN_MS, START, END, false, points, null,
                Instant.ofEpochMilli(earlier), END);
        assertEquals(7, future.coverage().expectedCount());
        assertTrue(future.coverage().droppedUnclosed());
        assertTrue(future.coverage().rangeBeyondNow());
        assertFalse(future.coverage().rangeComplete());
    }

    @Test
    void rangeBeyondRequestTimeIsPartialCoverage() {
        // “今天全天”在中午查询：已完结部分齐全，但请求区间延伸到 requestTime 之后，
        // rangeComplete=false 且 coveredUntil 明显小于请求终点
        long now = START + 6 * FIVE_MIN_MS + 60_000L; // 前 6 个已完结
        SeriesValidation v = validate(fullSequence(), false, null, now);
        assertTrue(v.coverage().complete());
        assertFalse(v.coverage().rangeComplete());
        assertTrue(v.coverage().droppedUnclosed());
        assertEquals(6, v.coverage().expectedCount());
        assertEquals(START + 6 * FIVE_MIN_MS, (long) v.coverage().coveredUntilMs());
    }
}
