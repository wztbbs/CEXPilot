package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 YAML + 本地模型脚本；验证路径错误在执行前修复，不访问交易所或模型。 */
class OutputReferenceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    private static final List<ToolDefinition> DEFINITIONS = ToolDefinitionLoader.load(LOADER);

    @ParameterizedTest
    @CsvSource({"price_usdt,value", "coverage.range_complete,value", "candles,observation_seconds"})
    void badMetricReferenceFailsBeforeExecutionAndCorrectPathPasses(String bad, String good) throws Exception {
        var registry = MetricTestSupport.registry();
        var compiler = new com.cexpilot.metric.MetricPlanCompiler(new com.cexpilot.metric.MetricCatalog(LOADER),
                registry, MetricTestSupport.providers());
        var validator = new PlanValidator(registry, new DagConfig());
        for (String field : List.of(bad, good)) {
            var plan = MetricTestSupport.plan(MetricTestSupport.metric("m1", "price.close", "range_statistic", "binance"));
            MetricTestSupport.calculation(plan, "c1", "difference",
                    "{\"left\":\"{{m1.binance." + field + "}}\",\"right\":1}");
            var errors = validator.validate(compiler.compile(plan, 8), compiler.allowedTools(), 8);
            if (field.equals(bad)) {
                // 编译器已把逻辑引用换成物理引用，错误信息里带物理字段路径，修复话术再由 logicalErrors 转回逻辑形式。
                assertEquals(1, errors.size(), errors.toString());
                assertTrue(errors.get(0).contains(bad), errors.get(0));
                assertTrue(errors.get(0).contains("输出契约"), errors.get(0));
            } else {
                assertTrue(errors.isEmpty(), errors.toString());
            }
        }
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
        var planner = new DagPlanner(llm, registry, new LlmConfig(), config, prompts, new PlanValidator(registry, config),
                MetricTestSupport.providers());
        var executor = new DagExecutor(registry, config, MetricTestSupport.providers(provider));
        try {
            var outcome = new DagRuntime(llm, planner, executor, prompts, Clock.systemUTC())
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
        var registry = MetricTestSupport.registry();
        List<List<ChatMessage>> seen = new ArrayList<>();
        LlmClient llm = (messages, tools) -> {
            seen.add(List.copyOf(messages));
            return new ChatResponse(metricEnvelope("price_usdt"), List.of(), 1, 1);
        };
        var config = new DagConfig();
        config.setPlannerMaxRetries(1);
        var prompts = new PromptStore(LOADER);
        var planner = new DagPlanner(llm, registry, new LlmConfig(), config, prompts, new PlanValidator(registry, config),
                MetricTestSupport.providers());
        var executor = new DagExecutor(registry, config);
        try {
            var outcome = new DagRuntime(llm, planner, executor, prompts, Clock.systemUTC())
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
        var compiler = new com.cexpilot.metric.MetricPlanCompiler(new com.cexpilot.metric.MetricCatalog(LOADER), registry,
                MetricTestSupport.providers());
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
        // 算子描述里出现的引用必须仍是标准指标/算子字段，防止目录改版后描述漂移回物理路径。
        for (String operator : List.of("avg", "sum", "min", "max", "compare", "difference", "ratio", "relative_change", "annualize")) {
            String description = DEFINITIONS.stream().filter(d -> d.name().equals(operator)).findFirst().orElseThrow().description();
            for (var ref : ReferenceResolver.findRefs(MAPPER.getNodeFactory().textNode(description))) {
                assertFalse(ref.path().startsWith(".data."), operator + " " + ref.path());
                assertFalse(ref.path().contains("statistics"), operator + " " + ref.path());
                assertFalse(ref.path().contains("columns"), operator + " " + ref.path());
                assertTrue(ref.path().endsWith(".value") || ref.path().endsWith(".observation_seconds")
                        || ref.path().endsWith(".period_seconds")
                        || ref.path().endsWith(".samples") || ref.path().matches(".*\\.samples(\\.\\d+)?\\.value")
                        || ref.path().endsWith(".percent"), operator + " " + ref.path());
            }
        }
    }
}
