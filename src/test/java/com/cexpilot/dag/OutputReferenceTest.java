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

    private static String metricEnvelope(String field) {
        var plan = MetricTestSupport.plan(MetricTestSupport.metric("m1", "price.close", "range_statistic", "binance"));
        MetricTestSupport.calculation(plan, "n2", "difference", "{\"left\":\"{{m1.binance." + field + "}}\",\"right\":1}");
        return MetricTestSupport.envelope(plan);
    }

    @Test
    void repairsBeforeAnyMarketCallAndOnlyComputedFactsReachAnswer() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = MetricTestSupport.registry();
        var provider = MetricTestSupport.provider((q,c) -> {
            calls.incrementAndGet();
            var result = (com.cexpilot.metric.MetricResult.Scalar)MetricTestSupport.metricResult(q,c,24,true);
            return new com.cexpilot.metric.MetricResult.Scalar(result.metadata(),new BigDecimal("101.25"),result.observationSeconds(),result.actualRange());
        });
        List<List<ChatMessage>> seen = new ArrayList<>();
        List<TraceEvent> events = new ArrayList<>();
        LlmClient llm = (messages, tools) -> {
            seen.add(List.copyOf(messages));
            String response;
            if (seen.size() <= 2) {
                assertEquals(0, calls.get());
                response = metricEnvelope(seen.size() == 1 ? "price_usdt" : "value");
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
        var executor = new DagExecutor(registry, config, MetricTestSupport.providers(provider));
        try {
            var outcome = new DagRuntime(llm, planner, executor, prompts, intents, Clock.systemUTC())
                    .execute("币安 BTC 价格减去我给定的 1 USDT 是多少？", "", "ref-repair", events::add);
            assertEquals(3, seen.size());
            assertEquals(2, outcome.toolCallCount());
            assertEquals(1, calls.get());
            assertEquals(1, events.stream().filter(e -> "METRIC_RESULT".equals(e.eventType())).count());
            assertFalse(events.stream().anyMatch(e -> "get_market_statistics".equals(e.name())));
            String repair = seen.get(1).get(seen.get(1).size() - 1).content();
            assertTrue(repair.contains("输出契约"));
            assertTrue(repair.contains("value"));
            assertTrue(repair.contains("{{m1.binance.price_usdt}}"));
            assertFalse(repair.contains("get_market_statistics"));
            assertFalse(repair.contains("{{metric_0.data."));
            assertEquals(1, events.stream().filter(e -> "PLAN".equals(e.eventType()) && e.error() != null).count());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void exhaustedRepairsExecuteNeitherToolsNorAnswer() {
        var registry = MetricTestSupport.registry(stub("get_klines"), stub("get_market_statistics"));
        List<List<ChatMessage>> seen = new ArrayList<>();
        LlmClient llm = (messages, tools) -> {
            seen.add(List.copyOf(messages));
            return new ChatResponse(metricEnvelope("price_usdt"), List.of(), 1, 1);
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
        var registry = MetricTestSupport.registry();
        var compiler = new com.cexpilot.metric.MetricPlanCompiler(new com.cexpilot.metric.MetricCatalog(LOADER), registry);
        var validator = new PlanValidator(registry, new DagConfig());
        String prompt;
        try (var stream = LOADER.getResource("classpath:prompts/dag_planner.txt").getInputStream()) {
            prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var matcher = Pattern.compile("(?m)^\\{\"in_domain\"").matcher(prompt);
        int count = 0;
        while (matcher.find()) {
            JsonNode plan = MAPPER.readTree(prompt.substring(matcher.start())).path("plan");
            if (plan.path("metrics").isEmpty() && plan.path("calculations").isEmpty()) continue;
            var errors = validator.validate(compiler.compile(plan, 8), compiler.allowedTools(), 8);
            assertTrue(errors.isEmpty(), errors.toString());
            count++;
        }
        assertEquals(3, count);
        for (String tool : List.of("difference", "avg", "sum", "min", "max", "compare", "ratio", "relative_change")) {
            String description = DEFINITIONS.stream().filter(d -> d.name().equals(tool)).findFirst().orElseThrow().description();
            for (var ref : ReferenceResolver.findRefs(MAPPER.getNodeFactory().textNode(description))) {
                String source = ref.path().startsWith(".data.rates") ? "get_funding_rate_history"
                        : ref.path().startsWith(".data.statistics") ? "get_market_statistics" : "get_ticker";
                assertNull(ToolOutputSchema.referenceError(DEFINITIONS.stream().filter(d -> d.name().equals(source)).findFirst().orElseThrow().outputSchema(), ref.path()), tool + ref.path());
            }
        }
    }
}
