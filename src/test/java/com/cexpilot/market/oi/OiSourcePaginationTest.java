package com.cexpilot.market.oi;

import com.cexpilot.market.model.OiPoint;
import com.cexpilot.time.OiInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OI 分页行为：fake PageFetcher 验证游标推进、去重排序、中止原因保留。
 */
class OiSourcePaginationTest {

    private static final long ONE_H_MS = 3_600_000L;
    private static final long START = 1_700_006_400_000L; // 网格对齐起点

    private static List<OiPoint> sequence(long fromTimestamp, int count) {
        List<OiPoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new OiPoint(fromTimestamp + i * ONE_H_MS, BigDecimal.ONE));
        }
        return points;
    }

    @Test
    void binanceMergesPagesBackwardInOrder() {
        // 接口返回靠 endTime 的最新 N 条：第一页 = 最新 500 条，第二页 = 更早的 4 条
        long end = START + 504L * ONE_H_MS;
        BinanceOiSource source = new BinanceOiSource((symbol, period, startTime, endTime, limit) ->
                endTime == end - 1 ? sequence(START + 4L * ONE_H_MS, limit)
                        : sequence(START, 4));
        OiSource.FetchResult result = source.fetch("BTC", OiInterval.ONE_HOUR, START, end);
        assertNull(result.abortReason());
        assertEquals(504, result.points().size());
        assertEquals(START, result.points().get(0).timestamp());
        assertEquals(START + 503L * ONE_H_MS, result.points().get(503).timestamp());
    }

    @Test
    void binanceAbortsWhenBudgetExhausted() {
        // 每页都满且持续向过去推进：8 页预算耗尽后中止，区间仍未拉完
        BinanceOiSource source = new BinanceOiSource((symbol, period, startTime, endTime, limit) ->
                sequence(endTime - 499L * ONE_H_MS, limit));
        OiSource.FetchResult result = source.fetch("BTC", OiInterval.ONE_HOUR,
                START, START + 5000L * ONE_H_MS);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("预算"));
        assertEquals(4000, result.points().size());
    }

    @Test
    void binanceAbortsWhenCursorStalls() {
        // 每次都返回相同的满页：最旧 ts 不再向过去推进
        long end = START + 1000L * ONE_H_MS;
        BinanceOiSource source = new BinanceOiSource((symbol, period, startTime, endTime, limit) ->
                sequence(end - 500L * ONE_H_MS, limit));
        OiSource.FetchResult result = source.fetch("BTC", OiInterval.ONE_HOUR, START, end);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"));
    }

    @Test
    void okxPaginatesBackwardAndSortsAscending() {
        long end = START + 105L * ONE_H_MS;
        OkxOiSource source = new OkxOiSource((instId, period, begin, e, limit) -> {
            if (e == end) {
                return sequence(START + 5L * ONE_H_MS, 100);
            }
            return sequence(START, 5);
        });
        OiSource.FetchResult result = source.fetch("BTC", OiInterval.ONE_HOUR, START, end);
        assertNull(result.abortReason());
        assertEquals(105, result.points().size());
        assertEquals(START, result.points().get(0).timestamp());
        assertEquals(end - ONE_H_MS, result.points().get(104).timestamp());
    }

    @Test
    void okxAbortsWhenCursorStalls() {
        long end = START + 300L * ONE_H_MS;
        OkxOiSource source = new OkxOiSource((instId, period, begin, e, limit) ->
                sequence(end - 100L * ONE_H_MS, 100));
        OiSource.FetchResult result = source.fetch("BTC", OiInterval.ONE_HOUR, START, end);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"));
    }
}
