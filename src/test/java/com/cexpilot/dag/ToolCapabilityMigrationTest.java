package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.dag.guard.QueryCapabilityGuard;
import com.cexpilot.intent.IntentRegistry;
import com.cexpilot.llm.*;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.funding.*;
import com.cexpilot.market.markprice.*;
import com.cexpilot.market.model.*;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.market.tool.*;
import com.cexpilot.market.trade.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.cexpilot.time.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 YAML / planner / executor / tool / answer 输入链路验证能力校验迁移。 */
class ToolCapabilityMigrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");
    private static final long YESTERDAY = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();
    private static final String DAY = """
            {"type":"calendar_period","unit":"day","offset":-1,"segment":"full","extent":"full_period"}
            """;

    private static ToolRegistry registry(AgentTool... tools) {
        Set<String> names = new HashSet<>();
        for (AgentTool tool : tools) names.add(tool.name());
        var definitions = ToolDefinitionLoader.load(new DefaultResourceLoader()).stream()
                .filter(d -> names.contains(d.name())).toList();
        return new ToolRegistry(List.of(tools), definitions);
    }

    private static final class Script implements LlmClient {
        private final String plan;
        private final List<List<ChatMessage>> calls = new ArrayList<>();
        private JsonNode responseFormat;
        Script(String nodes) {
            plan = "{\"in_domain\":true,\"intent\":\"MARKET_LOOKUP\",\"query_requirements\":"
                    + "{\"requires_period_comparison\":false},\"reply\":null,\"plan\":{\"nodes\":" + nodes + "}}";
        }
        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            calls.add(List.copyOf(messages));
            return new ChatResponse(calls.size() == 1 ? plan : "测试回答", List.of(), 1, 1);
        }
        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, JsonNode format) {
            responseFormat = format;
            return chat(messages, tools);
        }
        JsonNode answerFacts() throws Exception {
            assertEquals(2, calls.size());
            String content = calls.get(1).get(1).content();
            return MAPPER.readTree(content.substring(content.indexOf("<FACTS>") + 7, content.indexOf("</FACTS>")));
        }
    }

    private static ExecutionResult run(Script llm, ToolRegistry registry, String question) {
        var loader = new DefaultResourceLoader();
        var config = new DagConfig();
        config.setPlannerResponseFormat("json_schema");
        var prompts = new PromptStore(loader);
        var intents = new IntentRegistry(loader);
        var planner = new DagPlanner(llm, registry, intents, new LlmConfig(), config, prompts,
                new PlanValidator(registry, config), QueryCapabilityGuard.defaults());
        var executor = new DagExecutor(registry, config);
        try {
            return new DagRuntime(llm, planner, executor, prompts, intents).execute(
                    question, "", "migration", event -> {}, null, new RequestContext(ZoneOffset.UTC, NOW));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void historicalPriceTradesAndCurrentSnapshotCanCoexistWithoutGlobalTimeClassification() throws Exception {
        MarkPriceSource prices = new MarkPriceSource() {
            public Exchange exchange() { return Exchange.BINANCE; }
            public SeriesCapability capability() { return new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4); }
            public FetchResult fetch(String base, PriceType type, CandleInterval interval, long start, long end) {
                assertEquals(YESTERDAY, start);
                assertEquals(YESTERDAY + 86_400_000L, end);
                List<Candle> candles = new ArrayList<>();
                for (long t = start; t < end; t += interval.duration().toMillis()) {
                    candles.add(new Candle(t, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, null));
                }
                return new FetchResult(candles, null);
            }
        };
        TradeSource trades = new TradeSource() {
            public Exchange exchange() { return Exchange.OKX; }
            public SeriesCapability capability() { return new SeriesCapability(Set.of(), 100, 50, 90); }
            public FetchResult fetch(String base, long start, long end) {
                assertEquals(YESTERDAY, start);
                assertEquals(YESTERDAY + 86_400_000L, end);
                return new FetchResult(List.of(new TradePoint("1", start + 1000, BigDecimal.TEN, BigDecimal.ONE, true)), null);
            }
        };
        var resolver = new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC));
        var market = mock(MarketDataService.class);
        when(market.markPrice(Exchange.BINANCE, "BTC")).thenReturn(
                new MarkPrice(BigDecimal.TEN, BigDecimal.TEN, null, 0, NOW.toEpochMilli(), NOW.toEpochMilli()));
        var registry = registry(new GetMarkPriceHistoryTool(market, new MarkPriceQueryService(List.of(prices), resolver)),
                new GetTradeHistoryTool(market, new TradeQueryService(List.of(trades), resolver)), new GetMarkPriceTool(market));
        var llm = new Script("""
                [{"id":"p","tool":"get_mark_price_history","args":{"symbol":"BTC","interval":"1h","time":%s}},
                 {"id":"t","tool":"get_trade_history","args":{"exchange":"okx","symbol":"BTC","time":%s}},
                 {"id":"s","tool":"get_mark_price","args":{"symbol":"BTC"}}]
                """.formatted(DAY, DAY));
        ExecutionResult result = run(llm, registry, "昨天标记价走势、OKX 成交明细，以及当前标记价");
        assertEquals(3, result.toolCallCount());
        for (JsonNode item : result.evidence()) assertTrue(item.path("ok").asBoolean(), item::toString);
        assertEquals(24, result.evidence().get(0).path("data").path("candle_count").asInt());
        assertTrue(result.evidence().get(1).path("data").path("complete").asBoolean());
        var properties = llm.responseFormat.at("/json_schema/schema/properties/query_requirements/properties");
        assertEquals(1, properties.size());
        assertTrue(properties.has("requires_period_comparison"));
        String prompt = llm.calls.get(0).get(0).content();
        assertFalse(prompt.contains("除 K 线/资金费率/持仓量"));
        assertFalse(prompt.contains("time_scope"));
        assertFalse(prompt.contains("优先于工具描述"));
        assertEquals(3, llm.answerFacts().size());
    }

    @Test
    void unsupportedMarketPassesStructuralValidationButFailsBeforeMarketCall() throws Exception {
        var market = mock(MarketDataService.class);
        var tool = new GetMarkPriceTool(market);
        var registry = registry(tool);
        for (String extra : List.of("\"quote_asset\":\"USDC\"", "\"market_type\":\"spot\"", "\"market_type\":\"delivery\"")) {
            var llm = new Script("[{\"id\":\"n1\",\"tool\":\"get_mark_price\",\"args\":{\"symbol\":\"BTC\"," + extra + "}}]");
            var result = run(llm, registry, "按指定市场查询 BTC");
            assertEquals(1, result.toolCallCount());
            assertFalse(result.evidence().get(0).path("ok").asBoolean());
            assertTrue(result.evidence().get(0).path("error").asText().contains("不会替换"));
            assertFalse(llm.answerFacts().get(0).path("ok").asBoolean());
        }
        verifyNoInteractions(market);
    }

    @Test
    void recentTradeAndFundingDetailsReachAnswerWithoutTruncation() throws Exception {
        var market = mock(MarketDataService.class);
        List<Trade> trades = IntStream.range(0, 50).mapToObj(i ->
                new Trade(NOW.toEpochMilli() - 50_000 + i * 1000L, BigDecimal.TEN, BigDecimal.ONE, true, "base")).toList();
        when(market.recentTrades(Exchange.BINANCE, "BTC", 50)).thenReturn(trades);
        var funding = mock(FundingQueryService.class);
        List<FundingRatePoint> rates = IntStream.range(0, 50).mapToObj(i ->
                new FundingRatePoint(new BigDecimal("0.0001"), NOW.toEpochMilli() - (50 - i) * 28_800_000L)).toList();
        when(funding.queryRecent(Exchange.BINANCE, "BTC", 50, NOW)).thenReturn(new FundingRecentResult(rates, 28_800_000L));
        var registry = registry(new GetRecentTradesTool(market), new GetFundingRateHistoryTool(market, funding));
        var llm = new Script("""
                [{"id":"t","tool":"get_recent_trades","args":{"symbol":"BTC","limit":50,"details":true},"include_details":true},
                 {"id":"f","tool":"get_funding_rate_history","args":{"symbol":"BTC","count":50},"include_details":true}]
                """);
        var result = run(llm, registry, "列出最近 50 笔成交和最近 50 期费率明细");
        assertEquals(2, result.toolCallCount());
        JsonNode facts = llm.answerFacts();
        assertEquals(50, facts.get(0).path("data").path("recent_trades").size());
        assertEquals(50, facts.get(1).path("data").path("rates").size());
        assertTrue(facts.get(0).path("data").path("sample_complete").asBoolean());
        assertTrue(facts.get(1).path("data").path("sample_complete").asBoolean());
        String prompt = llm.calls.get(0).get(0).content();
        assertTrue(prompt.contains("get_recent_trades 还必须设置 args.details=true"));
        assertTrue(prompt.contains("get_funding_rate_history 不添加 args.details"));
        assertThrows(IllegalArgumentException.class, () -> registry.prepareArguments("get_funding_rate_history",
                MAPPER.readTree("{\"symbol\":\"BTC\",\"count\":50,\"details\":true}")));
    }

    @Test
    void invalidCountsCannotBypassChecksViaDirectToolCalls() throws Exception {
        var market = mock(MarketDataService.class);
        var funding = mock(FundingQueryService.class);
        var recentTool = new GetRecentTradesTool(market);
        var fundingTool = new GetFundingRateHistoryTool(market, funding);
        for (String count : List.of("0", "-1", "101", "2147483648", "1.5", "\"50\"", "null")) {
            assertFalse(recentTool.execute(MAPPER.readTree("{\"symbol\":\"BTC\",\"exchange\":\"binance\",\"limit\":" + count + "}"), null).ok());
            assertFalse(fundingTool.execute(MAPPER.readTree("{\"symbol\":\"BTC\",\"exchange\":\"binance\",\"count\":" + count + "}"), null).ok());
        }
        for (String symbol : List.of("BTC-USDC", "BTC/USDC", "BTCUSDC", "BTC-USD-SWAP", "BTC-USDT-261225")) {
            assertFalse(recentTool.execute(MAPPER.createObjectNode().put("symbol", symbol).put("exchange", "binance"), null).ok());
        }
        // time/count 按字段是否提供互斥；无效 count 也不能被 time 模式静默忽略。
        assertFalse(fundingTool.execute(MAPPER.readTree("{\"symbol\":\"BTC\",\"exchange\":\"binance\",\"count\":0,\"time\":" + DAY + "}"), null).ok());
        verifyNoInteractions(market, funding);
    }

    @Test
    void incompleteSampleRemainsExplicitInAnswerFacts() throws Exception {
        var market = mock(MarketDataService.class);
        when(market.recentTrades(Exchange.BINANCE, "BTC", 50)).thenReturn(List.of(
                new Trade(NOW.toEpochMilli(), BigDecimal.TEN, BigDecimal.ONE, true, "base")));
        var llm = new Script("[{\"id\":\"t\",\"tool\":\"get_recent_trades\",\"args\":{\"symbol\":\"BTC\",\"limit\":50}}]");
        run(llm, registry(new GetRecentTradesTool(market)), "最近 50 笔成交情况");
        JsonNode facts = llm.answerFacts().get(0).path("data");
        assertFalse(facts.path("sample_complete").asBoolean());
        assertEquals(50, facts.path("requested_count").asInt());
        assertEquals(1, facts.path("actual_count").asInt());
        assertTrue(facts.path("actual_count_note").asText().contains("非完整"));
    }

    @Test
    void timeAndBoundaryErrorsAreRejectedByQueryServiceBeforeFetching() {
        var source = mock(MarkPriceSource.class);
        when(source.exchange()).thenReturn(Exchange.BINANCE);
        when(source.capability()).thenReturn(new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4));
        var resolver = new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC));
        var tool = new GetMarkPriceHistoryTool(null, new MarkPriceQueryService(List.of(source), resolver));
        for (String time : List.of(
                "{\"type\":\"unsupported\"}",
                // 过去一小时为 11:03～12:03，exact + 1h 不允许未经对齐的边界。
                "{\"type\":\"rolling_window\",\"duration\":{\"value\":1,\"unit\":\"hour\"}}")) {
            var llm = new Script("[{\"id\":\"p\",\"tool\":\"get_mark_price_history\",\"args\":{\"symbol\":\"BTC\",\"interval\":\"1h\",\"time\":" + time + "}}]");
            var result = run(llm, registry(tool), "按指定时间查标记价格");
            assertEquals(1, result.toolCallCount());
            assertFalse(result.evidence().get(0).path("ok").asBoolean());
        }
        verify(source, never()).fetch(anyString(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void fundingSampleCountAndPaginationFailuresAreCheckedInService() {
        var source = mock(FundingRateSource.class);
        when(source.exchange()).thenReturn(Exchange.BINANCE);
        var service = new FundingQueryService(List.of(source), new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC)));
        assertThrows(IllegalArgumentException.class, () -> service.queryRecent(Exchange.BINANCE, "BTC", 101, NOW));
        verify(source, never()).fundingIntervalMs(anyString());
        when(source.fundingIntervalMs("BTC")).thenReturn(28_800_000L);
        when(source.capability()).thenReturn(new SeriesCapability(Set.of(), 1000, 10));
        when(source.fetch(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(
                new FundingRateSource.FetchResult(List.of(new FundingRatePoint(BigDecimal.ONE, NOW.toEpochMilli() - 1000)), "分页中止"));
        var error = assertThrows(IllegalArgumentException.class, () -> service.queryRecent(Exchange.BINANCE, "BTC", 1, NOW));
        assertTrue(error.getMessage().contains("完整性"));
    }
}
