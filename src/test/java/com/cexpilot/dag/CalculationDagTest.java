package com.cexpilot.dag;

import com.cexpilot.calculation.AvgTool;
import com.cexpilot.calculation.AnnualizeTool;
import com.cexpilot.calculation.RelativeChangeTool;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 YAML、规划校验和执行器；取数与模型响应均为本地假数据，不访问线上。 */
class CalculationDagTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentTool source(String name, String json) throws Exception {
        JsonNode facts = MAPPER.readTree(json);
        return new AgentTool() {
            public String name() { return name; }
            public ToolResult execute(JsonNode args, ToolContext ctx) { return ToolResult.success(facts); }
        };
    }

    private static ToolRegistry registry(AgentTool source) {
        List<AgentTool> tools = List.of(source, new AvgTool(), new RelativeChangeTool(), new AnnualizeTool());
        Set<String> names = tools.stream().map(AgentTool::name).collect(Collectors.toSet());
        var definitions = ToolDefinitionLoader.load(new DefaultResourceLoader()).stream()
                .filter(d -> names.contains(d.name())).toList();
        return new ToolRegistry(tools, definitions);
    }

    private static DagPlan plan(String json) throws Exception {
        return DagPlan.fromJson(MAPPER.readTree(json));
    }

    private static DagPlan samplePlan() throws Exception {
        return plan("""
                {"nodes":[
                  {"id":"history","tool":"get_funding_rate_history","args":{"symbol":"BTC","count":2}},
                  {"id":"mean","tool":"avg","args":{"input":{"kind":"field","collection":"{{history.data.rates}}",
                     "columns":"{{history.data.rates_columns}}","field":"rate"}},"depends_on":["history"]},
                  {"id":"change","tool":"relative_change","args":{"input":{"current":"{{mean.data.value}}",
                     "baseline":"{{history.data.rates.0.1}}"}},"depends_on":["mean","history"]}
                ]}
                """);
    }

    @Test
    void arrayProjectionAndChainedDecimalResultsReachTrace() throws Exception {
        var registry = registry(source("get_funding_rate_history", """
                {"sample_complete":true,"rates_columns":["settle_time","rate"],
                 "rates":[["t1","0.0001"],["t2","0.0003"]]}
                """));
        var plan = samplePlan();
        assertTrue(new PlanValidator(registry, new DagConfig()).validate(plan, null, 8).isEmpty());
        var executor = new DagExecutor(registry, new DagConfig());
        List<TraceEvent> events = new ArrayList<>();
        try {
            var outcome = executor.execute(plan, "calculation", events::add);
            assertEquals(3, outcome.layers());
            assertEquals("0.0002", outcome.context().get("mean").data().path("value").asText());
            var change = outcome.context().get("change");
            assertTrue(change.ok(), change::error);
            assertEquals("1", change.data().path("value").asText());
            assertEquals("100", change.data().path("percent").asText());
            assertEquals(3, events.size());
            var input = MAPPER.readTree(events.get(2).inputJson());
            assertEquals("0.0002", input.at("/args/input/current").asText());
            assertEquals("(current - baseline) / baseline",
                    MAPPER.readTree(events.get(2).outputJson()).at("/data/formula").asText());
        } finally {
            executor.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"sample_complete\":false", "\"coverage\":{\"range_complete\":false}",
            "\"complete\":false", "\"statistics_omitted\":\"缺口\""
    })
    void incompleteSourcesCannotBecomeSuccessfulAggregates(String completeness) throws Exception {
        var registry = registry(source("get_funding_rate_history", "{" + completeness
                + ",\"rates_columns\":[\"settle_time\",\"rate\"],\"rates\":[[\"t1\",\"0.0001\"]]}"));
        var executor = new DagExecutor(registry, new DagConfig());
        try {
            var context = executor.execute(samplePlan(), "partial", event -> {}).context();
            assertTrue(context.get("history").ok());
            assertFalse(context.get("mean").ok());
            assertNull(context.get("mean").data());
            assertTrue(context.get("change").error().contains("上游节点失败"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void relativeChangeAlsoRejectsIncompleteSourceEvenWhenScalarExists() throws Exception {
        var registry = registry(source("get_funding_rate_history", "{\"sample_complete\":false,\"value\":100}"));
        var executor = new DagExecutor(registry, new DagConfig());
        try {
            var context = executor.execute(plan("""
                    {"nodes":[
                     {"id":"h","tool":"get_funding_rate_history","args":{"symbol":"BTC","count":2}},
                     {"id":"c","tool":"relative_change","args":{"input":{"current":"{{h.data.value}}","baseline":50}},"depends_on":["h"]}
                    ]}
                    """), "partial-scalar", event -> {}).context();
            assertFalse(context.get("c").ok());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void validatesNestedArgumentsAndDependencyClosureBeforeExecution() throws Exception {
        var registry = registry(source("get_funding_rate_history", "{}"));
        var validator = new PlanValidator(registry, new DagConfig());
        for (String args : List.of(
                "{\"input\":{\"kind\":\"values\",\"values\":[]}}",
                "{\"input\":{\"kind\":\"field\",\"collection\":\"{{h.data.rates}}\"}}")) {
            var invalid = plan("{\"nodes\":[{\"id\":\"a\",\"tool\":\"avg\",\"args\":" + args + "}]}");
            assertFalse(validator.validate(invalid, null, 8).isEmpty());
        }
        var missingDependency = plan("""
                {"nodes":[
                 {"id":"h","tool":"get_funding_rate_history","args":{"symbol":"BTC","count":2}},
                 {"id":"a","tool":"avg","args":{"input":{"kind":"values","values":["{{h.data.value}}",2]}}}
                ]}
                """);
        assertTrue(validator.validate(missingDependency, null, 8).stream().anyMatch(e -> e.contains("depends_on")));
        assertFalse(validator.validate(plan("""
                {"nodes":[{"id":"c","tool":"relative_change","args":{"input":{"current":1,"baseline":0}}}]}
                """), null, 8).isEmpty());
    }

    @Test
    void annualizationRequiresExplicitMethodAndPassesResolvedFactsToAnswer() throws Exception {
        var registry = MetricTestSupport.registry();
        var provider = MetricTestSupport.provider((q,c) -> {
            var result = (com.cexpilot.metric.MetricResult.Scalar)MetricTestSupport.metricResult(q,c,24,true);
            return new com.cexpilot.metric.MetricResult.Scalar(result.metadata(),new java.math.BigDecimal("0.7"),
                    new java.math.BigDecimal("604800"),result.actualRange());
        });
        var logical = MetricTestSupport.plan(MetricTestSupport.metric("m1", "price.change_pct", "range_statistic", "binance"));
        MetricTestSupport.calculation(logical, "a", "annualize", """
                {"basis":"holding_return","method":"simple","rate":"{{m1.binance.value}}","rate_unit":"percent",
                 "period":{"value":"{{m1.binance.observation_seconds}}","unit":"second"}}
                """);
        String valid = MetricTestSupport.envelope(logical);
        List<List<ChatMessage>> seen = new ArrayList<>();
        List<TraceEvent> events = new ArrayList<>();
        LlmClient llm = (messages, tools) -> {
            seen.add(List.copyOf(messages));
            return new ChatResponse(switch (seen.size()) {
                case 1 -> valid.replace("\"method\":\"simple\",", "");
                case 2 -> valid;
                default -> "按 365 天、简单年化折算为 36.5%。";
            }, List.of(), 1, 1);
        };
        var loader = new DefaultResourceLoader();
        var prompts = new PromptStore(loader);
        var config = new DagConfig();
        var planner = new DagPlanner(llm, registry, new LlmConfig(), config, prompts,
                new PlanValidator(registry, config), MetricTestSupport.providers());
        var executor = new DagExecutor(registry, config, MetricTestSupport.providers(provider));
        try {
            var result = new DagRuntime(llm, planner, executor, prompts, Clock.systemUTC()).execute(
                    "币安 BTC 过去7天价格区间收益率简单年化多少？", "", "annual", events::add);
            assertEquals(3, seen.size());
            assertEquals(2, result.toolCallCount());
            assertTrue(seen.get(1).get(seen.get(1).size() - 1).content().contains("method 必须明确填写"));
            assertTrue(seen.get(0).get(0).content().contains("annualize:"));
            assertFalse(seen.get(0).get(0).content().contains("暂不支持年化"));
            var trace = events.stream().filter(e -> "annualize".equals(e.name())).findFirst().orElseThrow();
            assertEquals(604800, MAPPER.readTree(trace.inputJson()).at("/args/input/period/value").asInt());
            assertEquals("36.5", MAPPER.readTree(trace.outputJson()).at("/data/percent").asText());
            assertTrue(seen.get(2).get(1).content().contains("\"percent\":\"36.5\""));
        } finally {
            executor.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"coverage\":{\"range_complete\":false},\"statistics\":{\"sum\":0.007,\"observation_seconds\":604800}}",
            "{\"coverage\":{\"range_complete\":true},\"statistics\":{\"sum\":0.007,\"observation_seconds\":0}}",
            "{\"coverage\":{\"range_complete\":true},\"statistics\":{\"sum\":0.007}}"
    })
    void annualizationCannotUseIncompleteOrInvalidObservationWindows(String facts) throws Exception {
        var registry = registry(source("get_funding_rate_statistics", facts));
        var plan = plan("""
                {"nodes":[
                 {"id":"s","tool":"get_funding_rate_statistics","args":{"symbol":"BTC","time":{"type":"rolling_window","duration":{"value":7,"unit":"day"}}}},
                 {"id":"a","tool":"annualize","args":{"input":{"basis":"cumulative_rate","method":"simple",
                  "rate":"{{s.data.statistics.sum}}","rate_unit":"ratio",
                  "period":{"value":"{{s.data.statistics.observation_seconds}}","unit":"second"}}},"depends_on":["s"]}
                ]}
                """);
        assertTrue(new PlanValidator(registry, new DagConfig()).validate(plan, null, 8).isEmpty());
        var executor = new DagExecutor(registry, new DagConfig());
        try {
            var result = executor.execute(plan, "annual-failure", event -> {}).context().get("a");
            assertFalse(result.ok());
            assertNull(result.data());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void malformedPlansAreRepairedAndCalculationFactsReachAnswer() throws Exception {
        var registry = MetricTestSupport.registry();
        var logical = MetricTestSupport.scalarPlan();
        MetricTestSupport.calculation(logical, "c", "relative_change", "{\"current\":\"{{m1.binance.value}}\",\"baseline\":100}");
        MetricTestSupport.calculation(logical, "a", "avg", "{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\",100]}");
        String goodPlan = MetricTestSupport.envelope(logical);
        List<List<ChatMessage>> seen = new ArrayList<>();
        List<TraceEvent> events = new ArrayList<>();
        LlmClient llm = (messages, tools) -> {
            seen.add(List.copyOf(messages));
            String response = switch (seen.size()) {
                case 1 -> goodPlan.replace("\"baseline\":100", "\"baseline\":0");
                case 2 -> goodPlan;
                default -> "相对指定基准 100 增长 20%，等权均值为 110。";
            };
            return new ChatResponse(response, List.of(), 1, 1);
        };
        var loader = new DefaultResourceLoader();
        var prompts = new PromptStore(loader);
        var config = new DagConfig();
        var planner = new DagPlanner(llm, registry, new LlmConfig(), config, prompts,
                new PlanValidator(registry, config), MetricTestSupport.providers());
        var executor = new DagExecutor(registry, config, MetricTestSupport.providers());
        try {
            var result = new DagRuntime(llm, planner, executor, prompts, Clock.systemUTC()).execute(
                    "币安 BTC 昨日成交额相对我给定的 100 USDT 基准增长多少？两者等权平均是多少？", "", "facts", events::add);
            assertEquals(3, seen.size());
            assertEquals(3, result.toolCallCount());
            assertTrue(seen.get(1).get(seen.get(1).size() - 1).content().contains("baseline 必须大于 0"));
            String plannerPrompt = seen.get(0).get(0).content();
            assertTrue(plannerPrompt.contains("avg:"));
            assertTrue(plannerPrompt.contains("relative_change:"));
            assertFalse(plannerPrompt.contains("暂时无法计算同比"));
            String answerInput = seen.get(2).get(1).content();
            JsonNode facts = MAPPER.readTree(answerInput.substring(
                    answerInput.indexOf("<FACTS>") + 7, answerInput.indexOf("</FACTS>")));
            assertTrue(facts.toString().contains("\"percent\":\"20\""));
            assertTrue(facts.toString().contains("\"value\":\"110\""));
            assertEquals(1, events.stream().filter(e -> "PLAN".equals(e.eventType()) && e.error() != null).count());
        } finally {
            executor.shutdown();
        }
    }
}
