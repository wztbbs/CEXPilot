package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OpenInterestInfo;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.TraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 阶段 3b：price.last / oi.quantity 快照的编译准入、端到端取数与事实形态。 */
class Stage3SnapshotAccessTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final long DATA_TIME = Instant.parse("2026-09-29T11:59:30Z").toEpochMilli();
    private final List<DagExecutor> executors = new ArrayList<>();
    @AfterEach void close() { executors.forEach(DagExecutor::shutdown); }

    private static ObjectNode snapshot(String id, String name, String... exchanges) {
        return group(id, name, "snapshot", exchanges);
    }

    private static ObjectNode official24h(String id, String name, String... exchanges) {
        return group(id, name, "official_24h", exchanges);
    }

    private static ObjectNode group(String id, String name, String shape, String... exchanges) {
        ObjectNode metric = JSON.createObjectNode().put("id", id).put("metric", name).put("query_shape", shape);
        var ex = metric.putArray("exchanges");
        for (String exchange : exchanges) ex.add(exchange);
        metric.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        return metric;
    }

    private static MarketDataService market() {
        var market = mock(MarketDataService.class);
        when(market.ticker(eq(Exchange.BINANCE), anyString()))
                .thenReturn(new Ticker(new BigDecimal("100.5"), new BigDecimal("2.35"), new BigDecimal("10"),
                        new BigDecimal("1000"), false, DATA_TIME));
        when(market.ticker(eq(Exchange.OKX), anyString()))
                .thenReturn(new Ticker(new BigDecimal("98.5"), new BigDecimal("1.20"), new BigDecimal("9"),
                        new BigDecimal("900"), true, DATA_TIME));
        when(market.oiSnapshot(eq(Exchange.BINANCE), anyString()))
                .thenReturn(new OpenInterestInfo(new BigDecimal("104091.102"), "BTC", DATA_TIME, DATA_TIME));
        when(market.markPrice(any(Exchange.class), anyString())).thenReturn(new com.cexpilot.market.model.MarkPrice(
                new BigDecimal("65231.50"), new BigDecimal("65210.30"), new BigDecimal("0.0001"),
                1700035200000L, DATA_TIME, DATA_TIME + 60_000));
        when(market.orderBook(eq(Exchange.BINANCE), anyString(), anyInt())).thenReturn(orderBook("100.4"));
        when(market.orderBook(eq(Exchange.OKX), anyString(), anyInt())).thenReturn(orderBook("100.8"));
        return market;
    }

    private static MetricProviderRegistry providers() {
        return new MetricProviderRegistry(List.of(new TickerMetricProvider(market()),
                new OiMetricProvider(mock(OiQueryService.class), market()),
                new MarkPriceMetricProvider(mock(com.cexpilot.market.markprice.MarkPriceQueryService.class), market()),
                new OrderBookMetricProvider(market())));
    }

    /** 两所盘口：买一相同、卖一不同，价差分别为 0.5 与 0.9。 */
    private static com.cexpilot.market.model.OrderBook orderBook(String bestAsk) {
        return new com.cexpilot.market.model.OrderBook(
                java.util.List.of(new com.cexpilot.market.model.OrderBook.Level(new BigDecimal("99.9"), new BigDecimal("2"))),
                java.util.List.of(new com.cexpilot.market.model.OrderBook.Level(new BigDecimal(bestAsk), new BigDecimal("3"))),
                "base", DATA_TIME);
    }

    private ExecutionResult run(ObjectNode plan) {
        return run(plan, providers(), new ArrayList<>());
    }

    private ExecutionResult run(ObjectNode plan, MetricProviderRegistry providers, List<TraceEvent> events) {
        var llm = new Script(envelope(plan), "回答");
        var config = new DagConfig();
        var executor = new DagExecutor(registry(), config, providers);
        executors.add(executor);
        return new DagRuntime(llm, planner(llm, registry(), config), executor,
                new PromptStore(LOADER), Clock.fixed(NOW, ZoneOffset.UTC))
                .execute("问题", "", "t", events::add);
    }

    private static JsonNode result(ExecutionResult r, String id) {
        for (JsonNode node : r.evidence()) if (id.equals(node.path("node_id").asText())) return node;
        throw new AssertionError("missing " + id);
    }

    @Test void latestPriceExpandsBothExchangesAndFeedsDifference() {
        var plan = plan(snapshot("m1", "price.last", "binance", "okx"));
        calculation(plan, "c1", "difference",
                "{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m1.okx.value}}\"}");
        var events = new ArrayList<TraceEvent>();
        var r = run(plan, providers(), events);
        var binance = result(r, "metric_0").path("data");
        var okx = result(r, "metric_1").path("data");
        assertEquals(0, new BigDecimal("100.5").compareTo(binance.path("value").decimalValue()));
        assertEquals(0, new BigDecimal("98.5").compareTo(okx.path("value").decimalValue()));
        assertEquals("USDT", binance.path("unit").asText());
        assertEquals("2026-09-29 19:59:30", binance.path("as_of").asText());
        assertFalse(binance.has("requested_range"));
        assertFalse(binance.has("coverage"));
        assertEquals("price.last", binance.path("metric").asText());
        var calc = result(r, "c1").path("data");
        assertEquals(0, new BigDecimal("2").compareTo(new BigDecimal(calc.path("value").asText())));
        assertEquals("USDT", calc.path("unit").asText());
        assertEquals(2, calc.path("metric_sources").size());
        assertEquals(2, events.stream().filter(e -> "METRIC_RESULT".equals(e.eventType())).count());
        assertTrue(events.stream().noneMatch(e -> "TOOL_CALL".equals(e.eventType())
                && "get_ticker".equals(e.name())), "快照走 Provider，不再产生 get_ticker 调用");
    }

    @Test void oiSnapshotCarriesBaseUnitAndDataTime() {
        var r = run(plan(snapshot("m1", "oi.quantity", "binance")));
        var data = result(r, "metric_0").path("data");
        assertEquals(0, new BigDecimal("104091.102").compareTo(data.path("value").decimalValue()));
        assertEquals("BTC", data.path("unit").asText());
        assertEquals("oi", data.at("/source/provider").asText());
        assertEquals("QUANTITY", data.at("/source/selector").asText());
        assertEquals("2026-09-29 19:59:30", data.path("as_of").asText());
        assertEquals("snapshot", data.path("query_shape").asText());
    }

    @Test void snapshotRefusesWindowParametersThroughCompiler() {
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), providers());
        var withTime = snapshot("m1", "price.last", "binance");
        withTime.set("time", json(DAY));
        var error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(withTime), 8));
        assertTrue(error.getMessage().contains("time"), error.getMessage());
        var withInterval = snapshot("m1", "oi.quantity", "binance").put("interval", "1h");
        error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(withInterval), 8));
        assertTrue(error.getMessage().contains("interval"), error.getMessage());
        // 区间指标仍需要 time，不能被快照的宽松白名单带过去
        var range = snapshot("m1", "oi.quantity", "binance");
        range.put("query_shape", "time_series");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(range), 8));
    }

    @Test void official24hVolumeFeedsSumAcrossExchanges() {
        var plan = plan(official24h("m1", "trade.volume_24h", "binance", "okx"));
        calculation(plan, "c1", "sum", "{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\",\"{{m1.okx.value}}\"]}");
        var r = run(plan);
        var binance = result(r, "metric_0").path("data");
        var okx = result(r, "metric_1").path("data");
        assertEquals(0, new BigDecimal("10").compareTo(binance.path("value").decimalValue()));
        assertEquals(0, new BigDecimal("9").compareTo(okx.path("value").decimalValue()));
        assertEquals("BTC", binance.path("unit").asText());
        assertEquals("official_24h", binance.path("query_shape").asText());
        var calc = result(r, "c1").path("data");
        assertEquals(0, new BigDecimal("19").compareTo(new BigDecimal(calc.path("value").asText())));
        assertEquals("BTC", calc.path("unit").asText());
    }

    @Test void turnover24hRejectsOkxAtCompileTime() {
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), providers());
        var error = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plan(official24h("m1", "trade.turnover_24h", "binance", "okx")), 8));
        assertTrue(error.getMessage().contains("暂不支持交易所 okx"), error.getMessage());
        assertEquals(1, compiler.compile(plan(official24h("m1", "trade.turnover_24h", "binance")), 8).nodes().size());
    }

    @Test void markPriceSnapshotFeedsBasisDifference() {
        var prices = plan(snapshot("m1", "mark.price", "binance"), snapshot("m2", "index.price", "binance"));
        calculation(prices, "c1", "difference",
                "{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m2.binance.value}}\"}");
        var r = run(prices);
        var mark = result(r, "metric_0").path("data");
        var index = result(r, "metric_1").path("data");
        assertEquals(0, new BigDecimal("65231.50").compareTo(mark.path("value").decimalValue()));
        assertEquals(0, new BigDecimal("65210.30").compareTo(index.path("value").decimalValue()));
        // 两个价格各自带自己的来源时间，不能因为同一次快照就假装同时刻
        assertEquals("2026-09-29 19:59:30", mark.path("as_of").asText());
        assertEquals("2026-09-29 20:00:30", index.path("as_of").asText());
        assertEquals("mark.price", mark.path("metric").asText());
        var calc = result(r, "c1").path("data");
        assertEquals(0, new BigDecimal("21.20").compareTo(new BigDecimal(calc.path("value").asText())));
        assertEquals("USDT", calc.path("unit").asText());
    }

    @Test void orderbookDepthDefaultsAndBoundsAreEnforcedByBinding() {
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), providers());
        var plan = plan(snapshot("m1", "orderbook.spread", "binance"));
        var query = assertInstanceOf(SnapshotQuery.class,
                compiler.compile(plan, 8).nodes().get(0).metricQuery());
        assertEquals(20, query.depth(), "未指定档位时取绑定默认值");
        var explicit = snapshot("m1", "orderbook.spread", "binance").put("depth", 5);
        assertEquals(5, ((SnapshotQuery) compiler.compile(plan(explicit), 8).nodes().get(0).metricQuery()).depth());
        for (int invalid : new int[]{4, 51, 0}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> compiler.compile(plan(snapshot("m1", "orderbook.spread", "binance").put("depth", invalid)), 8));
            assertTrue(error.getMessage().contains("depth"), error.getMessage());
        }
        // 未声明 depth 的指标不能带 depth
        var error = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plan(snapshot("m1", "price.last", "binance").put("depth", 10)), 8));
        assertTrue(error.getMessage().contains("depth"), error.getMessage());
    }

    @Test void orderbookSpreadComparesAcrossExchanges() {
        var plan = plan(snapshot("m1", "orderbook.spread", "binance", "okx"));
        calculation(plan, "c1", "compare",
                "{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m1.okx.value}}\"}");
        var r = run(plan);
        assertEquals(0, new BigDecimal("0.5").compareTo(result(r, "metric_0").path("data").path("value").decimalValue()));
        assertEquals(0, new BigDecimal("0.9").compareTo(result(r, "metric_1").path("data").path("value").decimalValue()));
        assertEquals("USDT", result(r, "metric_1").path("data").path("unit").asText());
        assertEquals("less", result(r, "c1").path("data").path("relation").asText());
        assertEquals("orderbook", result(r, "metric_0").path("data").at("/source/provider").asText());
    }

    @Test void plannerPromptAnnouncesSnapshotCapability() {
        var llm = new Script(envelope(plan(snapshot("m1", "price.last", "binance"))), "回答");
        planner(llm, registry(), new DagConfig()).plan("现在 BTC 多少钱", "", "t", e -> {});
        String prompt = llm.calls.get(0).get(0).content();
        assertTrue(prompt.contains("price.last"), "提示词应出现快照指标");
        assertTrue(prompt.contains("snapshot"), "提示词应说明 snapshot 形态");
    }
}
