package com.cexpilot.market.taker;

import com.cexpilot.market.model.TakerVolumePoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * taker 成交量分页行为：fake PageFetcher 验证游标推进、去重排序、中止原因保留。
 */
class TakerVolumeSourcePaginationTest {

    private static final long FIVE_M_MS = 300_000L;
    private static final long START = 1_700_006_400_000L; // 5m 网格对齐起点

    private static List<TakerVolumePoint> sequence(long fromTimestamp, int count) {
        List<TakerVolumePoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new TakerVolumePoint(fromTimestamp + i * FIVE_M_MS,
                    BigDecimal.ONE, BigDecimal.ONE));
        }
        return points;
    }

    @Test
    void binanceMergesPagesBackwardInOrder() {
        // 接口返回靠 endTime 的最新 N 条：第一页 = 最新 500 条，第二页 = 更早的 4 条
        long end = START + 504L * FIVE_M_MS;
        BinanceTakerVolumeSource source = new BinanceTakerVolumeSource((symbol, startTime, endTime, limit) ->
                endTime == end - 1 ? sequence(START + 4L * FIVE_M_MS, limit)
                        : sequence(START, 4));
        TakerVolumeSource.FetchResult result = source.fetch("BTC", START, end);
        assertNull(result.abortReason());
        assertEquals(504, result.points().size());
        assertEquals(START, result.points().get(0).timestamp());
        assertEquals(START + 503L * FIVE_M_MS, result.points().get(503).timestamp());
    }

    @Test
    void binanceAbortsWhenBudgetExhausted() {
        // 每页都满且持续向过去推进：20 页预算耗尽后中止，区间仍未拉完
        BinanceTakerVolumeSource source = new BinanceTakerVolumeSource((symbol, startTime, endTime, limit) ->
                sequence(endTime - 499L * FIVE_M_MS, limit));
        TakerVolumeSource.FetchResult result = source.fetch("BTC", START, START + 10000L * FIVE_M_MS);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("预算"));
        assertEquals(10000, result.points().size());
    }

    @Test
    void binanceAbortsWhenCursorStalls() {
        // 每次都返回相同的满页：最旧 ts 不再向过去推进
        long end = START + 1000L * FIVE_M_MS;
        BinanceTakerVolumeSource source = new BinanceTakerVolumeSource((symbol, startTime, endTime, limit) ->
                sequence(end - 500L * FIVE_M_MS, limit));
        TakerVolumeSource.FetchResult result = source.fetch("BTC", START, end);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"));
    }

    @Test
    void okxPaginatesBackwardConvertsContractsAndSortsAscending() {
        long end = START + 105L * FIVE_M_MS;
        OkxTakerVolumeSource source = new OkxTakerVolumeSource((instId, begin, e, limit) -> {
            // begin 必须左移 1ms 以包含区间起点（OKX begin 不含等值）
            assertEquals(START - 1, begin);
            if (e == end) {
                return sequence(START + 5L * FIVE_M_MS, 100);
            }
            return sequence(START, 5);
        }, instId -> new BigDecimal("0.01"));
        TakerVolumeSource.FetchResult result = source.fetch("BTC", START, end);
        assertNull(result.abortReason());
        assertEquals(105, result.points().size());
        assertEquals(START, result.points().get(0).timestamp());
        assertEquals(end - FIVE_M_MS, result.points().get(104).timestamp());
        // ctVal=0.01：买卖各 1 张 → 0.01 币
        assertEquals(0, new BigDecimal("0.01").compareTo(result.points().get(0).buyVolume()));
        assertEquals(0, new BigDecimal("0.01").compareTo(result.points().get(0).sellVolume()));
    }

    @Test
    void okxAbortsWhenCursorStalls() {
        long end = START + 300L * FIVE_M_MS;
        OkxTakerVolumeSource source = new OkxTakerVolumeSource((instId, begin, e, limit) ->
                sequence(end - 100L * FIVE_M_MS, 100), instId -> BigDecimal.ONE);
        TakerVolumeSource.FetchResult result = source.fetch("BTC", START, end);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"));
    }
}
