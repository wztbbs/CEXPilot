package com.cexpilot.market.trade;

import com.cexpilot.market.model.TradePoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成交分页行为：fake PageFetcher 验证币安正向翻页、OKX 倒序游标翻页、
 * 去重、越界剔除、游标停滞中止与 OKX 张→币换算。
 */
class TradeSourcePaginationTest {

    private static final long G0 = 1_700_000_000_000L;

    /** id 为数字字符串（fromId + 序号），价格 100、数量 1 币、主动买。 */
    private static List<TradePoint> sequence(long fromId, long fromMs, int count) {
        List<TradePoint> trades = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            trades.add(new TradePoint(String.valueOf(fromId + i), fromMs + i,
                    new BigDecimal("100"), BigDecimal.ONE, true));
        }
        return trades;
    }

    @Test
    void binanceMergesPagesAndDeduplicatesByAggId() {
        BinanceTradeSource source = new BinanceTradeSource((symbol, fromId, startTime, endTime, limit) -> {
            if (fromId == null) {
                // 首页：按时间窗口定位；同毫秒多笔中额外的一笔排在最前（模拟真实升序页）
                assertEquals(G0, startTime);
                List<TradePoint> page = new ArrayList<>();
                page.add(new TradePoint("0", G0, new BigDecimal("100"), BigDecimal.ONE, false));
                page.addAll(sequence(1, G0, 1000));
                return page;
            }
            if (fromId == 1001L) {
                List<TradePoint> page = sequence(1001, G0 + 1000, 500);
                // 与首页重复的 ID：putIfAbsent 保留先到的一笔
                page.add(new TradePoint("6", G0 + 5, new BigDecimal("999"), new BigDecimal("999"), true));
                return page;
            }
            throw new IllegalStateException("unexpected fromId=" + fromId);
        });
        TradeSource.FetchResult result = source.fetch("BTC", G0, G0 + 2000);
        assertNull(result.abortReason());
        assertEquals(1501, result.trades().size());
        assertEquals(G0, result.trades().get(0).timestamp());
        // 升序
        for (int i = 1; i < result.trades().size(); i++) {
            assertTrue(result.trades().get(i).timestamp() >= result.trades().get(i - 1).timestamp());
        }
        // 重复 ID 保留先到的（qty=1，不是 999）
        TradePoint dup = result.trades().stream()
                .filter(t -> t.tradeId().equals("6")).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ONE.compareTo(dup.qty()));
    }

    @Test
    void binanceFromIdPaginationKeepsSameMillisecondTrades() {
        // CR01 回归：整页末尾与后续成交同毫秒（T=G0+999，ID 999~1002 共 4 笔）。
        // 旧实现按 lastTime+1 续页会漏掉 ID 1001、1002；按 fromId 续页必须全部保留。
        long t = G0 + 999;
        BinanceTradeSource source = new BinanceTradeSource((symbol, fromId, startTime, endTime, limit) -> {
            if (fromId == null) {
                List<TradePoint> page = sequence(1, G0, 998); // ID 1..998, 时间 G0..G0+997
                page.add(new TradePoint("999", t, new BigDecimal("100"), BigDecimal.ONE, true));
                page.add(new TradePoint("1000", t, new BigDecimal("100"), BigDecimal.ONE, true));
                return page; // 满页 1000，末两笔时间都是 T
            }
            assertEquals(1001L, fromId);
            return List.of(
                    new TradePoint("1001", t, new BigDecimal("100"), BigDecimal.ONE, false),
                    new TradePoint("1002", t, new BigDecimal("100"), BigDecimal.ONE, true));
        });
        TradeSource.FetchResult result = source.fetch("BTC", G0, G0 + 2000);
        assertNull(result.abortReason());
        assertEquals(1002, result.trades().size());
        // 同毫秒的 4 笔（含 1 笔主动卖）全部在结果中
        List<TradePoint> atT = result.trades().stream().filter(p -> p.timestamp() == t).toList();
        assertEquals(4, atT.size());
        assertEquals(1, atT.stream().filter(p -> !p.takerBuy()).count());
    }

    @Test
    void binanceAbortsWhenCursorStalls() {
        BinanceTradeSource source = new BinanceTradeSource(
                (symbol, fromId, startTime, endTime, limit) -> sequence(1, G0, 1000));
        TradeSource.FetchResult result = source.fetch("BTC", G0, G0 + 5000);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"), result.abortReason());
    }

    @Test
    void okxPaginatesBackwardAndConvertsContractsToBaseQty() {
        OkxTradeSource source = new OkxTradeSource((instId, type, after, limit) -> {
            if ("2".equals(type)) {
                // 首页：时间戳锚点必须等于区间终点（endMs）
                assertEquals(String.valueOf(G0 + 2000), after);
                return sequence(2000, G0 + 1000, 100); // 最新一页，ID 2000..2099
            }
            if ("1".equals(type) && after.equals("2000")) {
                return sequence(1000, G0, 50);
            }
            throw new IllegalStateException("unexpected type=" + type + " after=" + after);
        }, instId -> new BigDecimal("0.01"));
        TradeSource.FetchResult result = source.fetch("BTC", G0, G0 + 2000);
        assertNull(result.abortReason());
        assertEquals(150, result.trades().size());
        assertEquals(G0, result.trades().get(0).timestamp());
        assertEquals(G0 + 1099, result.trades().get(149).timestamp());
        // ctVal=0.01：sz=1 张 → 0.01 币
        assertEquals(0, new BigDecimal("0.01").compareTo(result.trades().get(0).qty()));
    }

    @Test
    void okxAbortsWhenCursorStalls() {
        OkxTradeSource source = new OkxTradeSource(
                (instId, type, after, limit) -> sequence(2000, G0 + 1000, 100), instId -> BigDecimal.ONE);
        TradeSource.FetchResult result = source.fetch("BTC", G0, G0 + 5000);
        assertNotNull(result.abortReason());
        assertTrue(result.abortReason().contains("游标"), result.abortReason());
    }

    @Test
    void okxDropsTradesOlderThanRangeStart() {
        // 单页含区间左端之前的数据：越界剔除，触及左端即停
        List<TradePoint> page = sequence(100, G0 - 10, 20); // G0-10 .. G0+9
        OkxTradeSource source = new OkxTradeSource((instId, type, after, limit) -> page, instId -> BigDecimal.ONE);
        TradeSource.FetchResult result = source.fetch("BTC", G0, G0 + 100);
        assertNull(result.abortReason());
        assertEquals(10, result.trades().size());
        assertEquals(G0, result.trades().get(0).timestamp());
    }
}
