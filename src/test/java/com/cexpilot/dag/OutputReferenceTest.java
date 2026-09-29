package com.cexpilot.dag;

import com.cexpilot.calculation.DifferenceTool;
import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.intent.IntentRegistry;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.market.tool.GetTickerTool;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 YAML + 本地模型脚本；验证路径错误在执行前修复，不访问交易所或模型。 */
class OutputReferenceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    private static final List<ToolDefinition> DEFINITIONS = ToolDefinitionLoader.load(LOADER);

    private static AgentTool stub(String name) {
        return new AgentTool() {
            public String name() { return name; }
            public ToolResult execute(JsonNode args, ToolContext context) {
                throw new AssertionError("静态校验不得执行工具");
            }
        };
    }

    private static ToolRegistry registry(List<AgentTool> tools) {
        Set<String> names = tools.stream().map(AgentTool::name).collect(Collectors.toSet());
        return new ToolRegistry(tools, DEFINITIONS.stream().filter(d -> names.contains(d.name())).toList());
    }

    private static String envelope(String source, String path) {
        return """
                {"in_domain":true,"intent":"MARKET_ANALYSIS","reply":null,"plan":{"nodes":[
                 {"id":"n1","tool":"%s","args":{"exchange":"binance","symbol":"BTC"%s}},
                 {"id":"n2","tool":"difference","args":{"input":{"left":"{{n1.%s}}","right":1}},"depends_on":["n1"]}
                ]}}
                """.formatted(source, source.equals("get_market_statistics")
                ? ",\"time\":{\"type\":\"calendar_period\",\"unit\":\"week\",\"offset\":-1}" : "", path);
    }

    @ParameterizedTest
    @CsvSource({
            "get_ticker,data.ticker.price_usdt,data.last_price",
            "get_funding_rate,data.current_rate,data.current_funding_rate",
            "get_market_statistics,data.statistics.close_price,data.statistics.close"
    })
    void reportedBadPathsFailBeforeExecutionAndCorrectPathsPass(String source, String bad, String good) throws Exception {
        var validator = new PlanValidator(registry(List.of(stub(source), new DifferenceTool())), new DagConfig());
        var errors = validator.validate(DagPlan.fromJson(MAPPER.readTree(envelope(source, bad)).path("plan")), null, 8);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("{{n1." + bad + "}}"));
        assertTrue(errors.get(0).contains(source));
        assertTrue(errors.get(0).contains(good.substring(good.lastIndexOf('.') + 1)), errors.toString());
        assertTrue(validator.validate(DagPlan.fromJson(MAPPER.readTree(envelope(source, good)).path("plan")), null, 8).isEmpty());
    }

    @Test
    void repairsBeforeAnyMarketCallAndOnlyComputedFactsReachAnswer() {
        MarketDataService market = mock(MarketDataService.class);
        when(market.ticker(Exchange.BINANCE, "BTC")).thenReturn(
                new Ticker(new BigDecimal("101.25"), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, false, 1));
        var registry = registry(List.of(new GetTickerTool(market), new DifferenceTool()));
        List<List<ChatMessage>> seen = new ArrayList<>();
        List<TraceEvent> events = new ArrayList<>();
        LlmClient llm = (messages, tools) -> {
            seen.add(List.copyOf(messages));
            String response;
            if (seen.size() <= 2) {
                verify(market, never()).ticker(any(), any());
                response = envelope("get_ticker", seen.size() == 1 ? "data.ticker.price_usdt" : "data.last_price");
            } else {
                assertTrue(messages.get(1).content().contains("\"value\":\"100.25\""));
                response = "相差 100.25 USDT。";
            }
            return new ChatResponse(response, List.of(), 1, 1);
        };
        var config = new DagConfig();
        var prompts = new PromptStore(LOADER);
        var intents = new IntentRegistry(LOADER);
        var planner = new DagPlanner(llm, registry, intents, new LlmConfig(), config, prompts, new PlanValidator(registry, config));
        var executor = new DagExecutor(registry, config);
        try {
            var outcome = new DagRuntime(llm, planner, executor, prompts, intents, Clock.systemUTC())
                    .execute("币安 BTC 价格减去我给定的 1 USDT 是多少？", "", "ref-repair", events::add);
            assertEquals(3, seen.size());
            assertEquals(2, outcome.toolCallCount());
            verify(market, times(1)).ticker(Exchange.BINANCE, "BTC");
            var tickerEvent = events.stream().filter(e -> "get_ticker".equals(e.name())).findFirst().orElseThrow();
            try {
                OutputContractAssertions.assertKnownPaths("get_ticker", MAPPER.readTree(tickerEvent.outputJson()).path("data"));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            String repair = seen.get(1).get(seen.get(1).size() - 1).content();
            assertTrue(repair.contains("输出契约"));
            assertTrue(repair.contains("last_price"));
            assertEquals(1, events.stream().filter(e -> "PLAN".equals(e.eventType()) && e.error() != null).count());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void exhaustedRepairsExecuteNeitherToolsNorAnswer() {
        var registry = registry(List.of(stub("get_ticker"), new DifferenceTool()));
        List<List<ChatMessage>> seen = new ArrayList<>();
        LlmClient llm = (messages, tools) -> {
            seen.add(List.copyOf(messages));
            return new ChatResponse(envelope("get_ticker", "data.price_usdt"), List.of(), 1, 1);
        };
        var config = new DagConfig();
        config.setPlannerMaxRetries(1);
        var prompts = new PromptStore(LOADER);
        var intents = new IntentRegistry(LOADER);
        var planner = new DagPlanner(llm, registry, intents, new LlmConfig(), config, prompts, new PlanValidator(registry, config));
        var executor = new DagExecutor(registry, config);
        try {
            var outcome = new DagRuntime(llm, planner, executor, prompts, intents, Clock.systemUTC())
                    .execute("查询价差", "", "ref-failure", event -> {});
            assertEquals(2, seen.size());
            assertEquals(0, outcome.toolCallCount());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void allPlannerExamplesUseRealOutputPaths() throws Exception {
        var tools = DEFINITIONS.stream().filter(ToolDefinition::enabled).map(d -> stub(d.name())).toList();
        var registry = registry(tools);
        var validator = new PlanValidator(registry, new DagConfig());
        String prompt;
        try (var stream = LOADER.getResource("classpath:prompts/dag_planner.txt").getInputStream()) {
            prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var matcher = Pattern.compile("(?m)^\\{\"in_domain\"").matcher(prompt);
        int count = 0;
        while (matcher.find()) {
            JsonNode plan = MAPPER.readTree(prompt.substring(matcher.start())).path("plan");
            if (plan.at("/nodes/0/tool").asText().equals("工具名")) continue;
            var errors = validator.validate(DagPlan.fromJson(plan), null, 8);
            assertTrue(errors.isEmpty(), errors.toString());
            count++;
        }
        assertTrue(count >= 10);
        for (String tool : List.of("difference", "avg", "sum", "min", "max", "compare", "ratio", "relative_change")) {
            String description = DEFINITIONS.stream().filter(d -> d.name().equals(tool)).findFirst().orElseThrow().description();
            for (var ref : ReferenceResolver.findRefs(MAPPER.getNodeFactory().textNode(description))) {
                String source = ref.path().startsWith(".data.rates") ? "get_funding_rate_history"
                        : ref.path().startsWith(".data.statistics") ? "get_market_statistics" : "get_ticker";
                assertNull(ToolOutputSchema.referenceError(registry.outputSchema(source), ref.path()), tool + ref.path());
            }
        }
    }
}
