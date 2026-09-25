package com.cexpilot.market.kline;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.Candle;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页行为：fake PageFetcher 模拟交易所分页，验证游标推进、去重排序、中止原因保留。
 */
class KlineSourcePaginationTest {

    private static final long FIVE_MIN_MS = 5 * 60_000L;
    private static final long START = 1_700_000_000_000L;

    private static Candle candle(long openTime) {
        return new Candle(openTime, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE);
    }

    /** 生成 [fromOpenTime, fromOpenTime + count×interval) 的连续 K 线。 */
    private static List<Candle> sequence(long fromOpenTime, int count) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candles.add(candle(fromOpenTime + i * FIVE_MIN_MS));
        }
        return candles;
    }

    private static KlineQueryRequest request(long startMs, long endMs) {
        return new KlineQueryRequest(Exchange.BINANCE, "BTC", CandleInterval.FIVE_MINUTES,
                new TimeRange(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs), ZoneOffset.UTC),
                false);
    }

    @Test
    void binanceMergesPagesInOrder() {
        // 两页：第一页满 1500 根，第二页不足页满即结束
        BinanceKlineSource source = new BinanceKlineSource((symbol, interval, startTime, endTime, limit) ->
                startTime == START ? sequence(START, limit)
                        : sequence(START + 1500L * FIVE_MIN_MS, 12));
        KlineSource.FetchResult result = source.fetch(
                request(START, START + 1512L * FIVE_MIN_MS));
        assertNull(result.abortReason());
        assertEquals(1512, result.candles().size());
        assertEquals(START, result.candles().get(0).openTime());
        assertEquals(START + 1511L * FIVE_MIN_MS, result.candles().get(1511).openTime());
    }

    @Test
    void binanceAbortsWhenCursorStalls() {
        // 每次都返回相同的满页：游标第二次不再推进
        BinanceKlineSource source = new BinanceKlineSource((symbol, interval, startTime, endTime, limit) ->
                sequence(START, limit));
        KlineSource.FetchResult result = source.fetch(
                request(START, START + 4000L * FIVE_MIN_MS));
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"));
        assertEquals(1500, result.candles().size());
    }

    @Test
    void binanceAbortsWhenBudgetExhausted() {
        // 每页都满且持续推进：4 页预算耗尽后中止，区间仍未拉完
        BinanceKlineSource source = new BinanceKlineSource((symbol, interval, startTime, endTime, limit) ->
                sequence(startTime, limit));
        KlineSource.FetchResult result = source.fetch(
                request(START, START + 7000L * FIVE_MIN_MS));
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("预算"));
        assertEquals(6000, result.candles().size());
    }

    @Test
    void okxPaginatesBackwardAndSortsAscending() {
        // 两页：从右端往过去翻，结果仍按 openTime 升序
        long end = START + 312L * FIVE_MIN_MS;
        Clock clock = Clock.fixed(Instant.ofEpochMilli(end), ZoneOffset.UTC);
        OkxKlineSource source = new OkxKlineSource((instId, bar, before, after, limit, history) -> {
            if (after == end) {
                return sequence(START + 12L * FIVE_MIN_MS, 300);
            }
            return sequence(START, 12);
        }, clock);
        KlineQueryRequest okxReq = new KlineQueryRequest(Exchange.OKX, "BTC", CandleInterval.FIVE_MINUTES,
                new TimeRange(Instant.ofEpochMilli(START), Instant.ofEpochMilli(end), ZoneOffset.UTC),
                false);
        KlineSource.FetchResult result = source.fetch(okxReq);
        assertNull(result.abortReason());
        assertEquals(312, result.candles().size());
        assertEquals(START, result.candles().get(0).openTime());
        assertEquals(end - FIVE_MIN_MS, result.candles().get(311).openTime());
    }

    @Test
    void okxAbortsWhenCursorStalls() {
        long end = START + 1000L * FIVE_MIN_MS;
        Clock clock = Clock.fixed(Instant.ofEpochMilli(end), ZoneOffset.UTC);
        // 每次都返回相同的满页：最旧 openTime 不早于 after 锚点
        OkxKlineSource source = new OkxKlineSource((instId, bar, before, after, limit, history) ->
                sequence(end - 300L * FIVE_MIN_MS, limit), clock);
        KlineQueryRequest okxReq = new KlineQueryRequest(Exchange.OKX, "BTC", CandleInterval.FIVE_MINUTES,
                new TimeRange(Instant.ofEpochMilli(START), Instant.ofEpochMilli(end), ZoneOffset.UTC),
                false);
        KlineSource.FetchResult result = source.fetch(okxReq);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"));
    }

    @Test
    void okxSplitsRangeAcrossHistoryAndRecentEndpoints() {
        // /market/candles 只覆盖最近 1440 根（5m=5 天）：区间跨分界时拆两段拉取并缝合
        long now = START + 2000L * FIVE_MIN_MS;
        long cutoff = now - 1440L * FIVE_MIN_MS; // START + 560 根
        long end = START + 1500L * FIVE_MIN_MS;  // 区间 [START, START+1500根)，跨分界
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        List<Boolean> endpoints = new ArrayList<>();
        OkxKlineSource source = new OkxKlineSource((instId, bar, before, after, limit, history) -> {
            endpoints.add(history);
            long segStart = history ? START : cutoff;
            int count = (int) ((Math.min(after, history ? cutoff : end) - segStart) / FIVE_MIN_MS);
            return sequence(segStart, count);
        }, clock);
        KlineQueryRequest okxReq = new KlineQueryRequest(Exchange.OKX, "BTC", CandleInterval.FIVE_MINUTES,
                new TimeRange(Instant.ofEpochMilli(START), Instant.ofEpochMilli(end), ZoneOffset.UTC),
                false);
        KlineSource.FetchResult result = source.fetch(okxReq);
        assertNull(result.abortReason());
        assertEquals(List.of(true, false), endpoints); // 历史段 + 近期段各一次
        assertEquals(1500, result.candles().size());
        assertEquals(START, result.candles().get(0).openTime());
        assertEquals(end - FIVE_MIN_MS, result.candles().get(1499).openTime());
    }
}
