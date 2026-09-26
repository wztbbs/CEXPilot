package com.cexpilot.market.taker;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesRangeFilter;
import com.cexpilot.market.tool.GetTradeFlowStatisticsTool;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeRangeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 用具有周期截断/开闭边界的接口替身验证；不以实现中的游标数值拼接预设页。 */
class TakerVolumeSourcePaginationTest {
    private static final long STEP = 300_000L;
    private static final long START = Instant.parse("2026-09-24T00:00:00Z").toEpochMilli();
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private static List<TakerVolumePoint> sequence(long start, int count) {
        List<TakerVolumePoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new TakerVolumePoint(start + i * STEP, BigDecimal.ONE, BigDecimal.ONE));
        }
        return points;
    }

    private static List<TakerVolumePoint> latest(List<TakerVolumePoint> data, long start, long end, int limit) {
        var matching = data.stream().filter(p -> p.timestamp() >= start && p.timestamp() <= end).toList();
        return matching.subList(Math.max(0, matching.size() - limit), matching.size());
    }

    // 模拟已观察到的行为：参数按 5m 边界取整后，对应前一周期的起点。
    private static List<TakerVolumePoint> binancePage(List<TakerVolumePoint> data, long start, long end, int limit) {
        return latest(data, Math.floorDiv(start, STEP) * STEP - STEP,
                Math.floorDiv(end, STEP) * STEP - STEP, limit);
    }

    private static List<TakerVolumePoint> inRange(TakerVolumeSource.FetchResult result, long end) {
        return SeriesRangeFilter.withinRange(result.points(),
                new TimeRange(Instant.ofEpochMilli(START), Instant.ofEpochMilli(end), ZoneOffset.UTC),
                TakerVolumePoint::timestamp);
    }

    @Test
    void wholeDayLastPeriodReachesToolStatisticsDespiteRoundedEndTime() throws Exception {
        long end = START + 288 * STEP;
        var data = sequence(START - 2 * STEP, 292);
        // 旧参数 end-1 确实漏掉末根，且原始条数 288 不能证明覆盖完整。
        var oldPage = binancePage(data, START, end - 1, 500);
        assertEquals(288, oldPage.size());
        assertEquals(end - 2 * STEP, oldPage.get(oldPage.size() - 1).timestamp());
        var source = new BinanceTakerVolumeSource((symbol, startTime, endTime, limit) ->
                binancePage(data, startTime, endTime, limit));
        var service = new TakerVolumeQueryService(List.of(source), new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC)));
        var args = new ObjectMapper().readTree("""
                {"exchange":"binance","symbol":"BTC","time":{
                  "type":"absolute_range","timezone":"UTC",
                  "start":{"year":2026,"month":9,"day":24,"time":"00:00:00"},
                  "end":{"year":2026,"month":9,"day":25,"time":"00:00:00"},"end_mode":"exclusive"}}
                """);
        var result = new GetTradeFlowStatisticsTool(null, service).execute(args, new ToolContext("test", null, ZoneOffset.UTC, NOW));
        assertTrue(result.ok(), result.error());
        assertTrue(result.data().at("/coverage/range_complete").asBoolean());
        assertEquals(288, result.data().at("/coverage/actual_count").asInt());
        assertEquals(0, result.data().at("/coverage/unexpected_count").asInt());
        assertEquals(288, result.data().at("/statistics/buy_volume").asInt());
        assertEquals(288, result.data().at("/statistics/sell_volume").asInt());
        assertEquals(0, new BigDecimal("0.5").compareTo(result.data().at("/statistics/buy_volume_ratio").decimalValue()));
        assertEquals("2026-09-24 23:55:00", result.data().at("/statistics/actual_range/end_inclusive").asText());
        assertFalse(result.data().has("statistics_omitted"));
    }

    @Test
    void bothExchangesOverlapPagesWithoutMissingOrDoubleCounting() {
        long end = START + 504 * STEP;
        var data = sequence(START - 2 * STEP, 510);
        for (Exchange exchange : Exchange.values()) {
            List<List<TakerVolumePoint>> pages = new ArrayList<>();
            TakerVolumeSource source = exchange == Exchange.BINANCE
                    ? new BinanceTakerVolumeSource((symbol, start, finish, limit) -> {
                        assertEquals(START - STEP, start);
                        var page = binancePage(data, start, finish, limit);
                        pages.add(page);
                        return page;
                    })
                    : new OkxTakerVolumeSource((symbol, begin, finish, limit) -> {
                        assertEquals(START - STEP, begin);
                        // OKX 左端不含等值，右端按含等值模拟；分页因此会返回重复点。
                        var page = latest(data, begin + 1, finish, limit);
                        pages.add(page);
                        return page;
                    }, symbol -> new BigDecimal("0.01"));
            var fetched = source.fetch("BTC", START, end, NOW);
            assertNull(fetched.abortReason());
            assertTrue(pages.size() > 1);
            assertTrue(pages.get(1).stream().anyMatch(p -> pages.get(0).contains(p)), "分页应有重叠");
            assertTrue(fetched.points().stream().anyMatch(p -> p.timestamp() >= end), "Source 应保留取数余量交给 Service 过滤");
            var actual = inRange(fetched, end);
            assertEquals(504, actual.size());
            for (int i = 0; i < actual.size(); i++) assertEquals(START + i * STEP, actual.get(i).timestamp());
            BigDecimal expected = exchange == Exchange.BINANCE ? BigDecimal.ONE : new BigDecimal("0.01");
            assertEquals(0, expected.compareTo(actual.get(0).buyVolume()));
            assertEquals(0, expected.compareTo(actual.get(0).sellVolume()));
        }
    }

    @Test
    void bothExchangesAbortWhenCursorStalls() {
        long end = START + 1000 * STEP;
        var binance = new BinanceTakerVolumeSource((s, start, finish, limit) -> sequence(end - 500 * STEP, 500));
        var okx = new OkxTakerVolumeSource((s, start, finish, limit) -> sequence(end - 100 * STEP, 100), s -> BigDecimal.ONE);
        for (var source : List.of(binance, okx)) {
            var fetched = source.fetch("BTC", START, end, Instant.ofEpochMilli(end + STEP));
            assertNotNull(fetched.abortReason());
            assertTrue(fetched.abortReason().contains("游标"));
        }
    }

    @Test
    void bothExchangesKeepRequestBudgetAndAbortReason() {
        long end = START + 10000 * STEP;
        for (Exchange exchange : Exchange.values()) {
            AtomicInteger calls = new AtomicInteger();
            TakerVolumeSource source = exchange == Exchange.BINANCE
                    ? new BinanceTakerVolumeSource((s, start, finish, limit) -> {
                        calls.incrementAndGet();
                        return sequence(finish - (limit - 1L) * STEP, limit);
                    })
                    : new OkxTakerVolumeSource((s, start, finish, limit) -> {
                        calls.incrementAndGet();
                        return sequence(finish - (limit - 1L) * STEP, limit);
                    }, s -> BigDecimal.ONE);
            var fetched = source.fetch("BTC", START, end, Instant.ofEpochMilli(end + STEP));
            assertEquals(source.capability().maxPages(), calls.get());
            assertNotNull(fetched.abortReason());
            assertTrue(fetched.abortReason().contains("预算"));
            assertTrue(inRange(fetched, end).size() < 10000);
        }
    }

    @Test
    void expansionRespectsRetentionAndRequestTime() {
        long now = NOW.toEpochMilli();
        long earliest = now - 30 * 86_400_000L;
        var source = new BinanceTakerVolumeSource((s, start, finish, limit) -> {
            assertEquals(earliest, start);
            assertEquals(now, finish);
            return List.of();
        });
        assertTrue(source.fetch("BTC", earliest, now, NOW).points().isEmpty());
    }
}
