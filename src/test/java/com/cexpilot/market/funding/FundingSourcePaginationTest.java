package com.cexpilot.market.funding;

import com.cexpilot.market.model.FundingRatePoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * funding 分页行为：fake PageFetcher 验证游标推进、去重排序、中止原因保留与结算时间网格吸附。
 */
class FundingSourcePaginationTest {

    private static final long EIGHT_H_MS = 8 * 3_600_000L;
    private static final long G0 = 1_700_006_400_000L; // 8h 网格对齐起点（28800000 的整数倍）

    static {
        assert G0 % EIGHT_H_MS == 0;
    }

    private static List<FundingRatePoint> sequence(long fromFundingTime, int count) {
        List<FundingRatePoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new FundingRatePoint(BigDecimal.ONE, fromFundingTime + i * EIGHT_H_MS));
        }
        return points;
    }

    @Test
    void binanceMergesPagesAndSnapsJitter() {
        // 满页后继续翻页；结算时间 +1ms 抖动被吸附回网格
        BinanceFundingRateSource source = new BinanceFundingRateSource((symbol, startTime, endTime, limit) -> {
            if (startTime == G0) {
                List<FundingRatePoint> page = sequence(G0, limit);
                page.set(1, new FundingRatePoint(BigDecimal.ONE, G0 + EIGHT_H_MS + 1)); // +1ms 抖动
                return page;
            }
            return sequence(startTime - 1 + EIGHT_H_MS, 2);
        }, symbol -> EIGHT_H_MS);
        long end = G0 + 1002 * EIGHT_H_MS;
        FundingRateSource.FetchResult result = source.fetch("BTC", EIGHT_H_MS, G0, end);
        assertNull(result.abortReason());
        assertEquals(1002, result.points().size());
        assertEquals(G0 + EIGHT_H_MS, result.points().get(1).fundingTime()); // 已吸附
    }

    @Test
    void binanceAbortsWhenCursorStalls() {
        BinanceFundingRateSource source = new BinanceFundingRateSource(
                (symbol, startTime, endTime, limit) -> sequence(G0, limit), symbol -> EIGHT_H_MS);
        FundingRateSource.FetchResult result = source.fetch("BTC", EIGHT_H_MS, G0, G0 + 3000 * EIGHT_H_MS);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"));
    }

    @Test
    void okxPaginatesBackwardAndSortsAscending() {
        long end = G0 + 105 * EIGHT_H_MS;
        OkxFundingRateSource source = new OkxFundingRateSource((instId, before, after, limit) -> {
            if (after == end) {
                return sequence(G0 + 5 * EIGHT_H_MS, 100);
            }
            return sequence(G0, 5);
        }, instId -> EIGHT_H_MS);
        FundingRateSource.FetchResult result = source.fetch("BTC", EIGHT_H_MS, G0, end);
        assertNull(result.abortReason());
        assertEquals(105, result.points().size());
        assertEquals(G0, result.points().get(0).fundingTime());
        assertEquals(end - EIGHT_H_MS, result.points().get(104).fundingTime());
    }

    @Test
    void gridSnapKeepsOutOfToleranceValues() {
        // 容差内（±60s）吸附到网格；超差不是抖动：保持原值，由覆盖核对报错位
        assertEquals(G0, SettlementGridSnap.snap(G0 + 1, EIGHT_H_MS));
        assertEquals(G0, SettlementGridSnap.snap(G0 - 59_999L, EIGHT_H_MS));
        long offGrid = G0 + 120_000L;
        assertEquals(offGrid, SettlementGridSnap.snap(offGrid, EIGHT_H_MS));
    }
}
