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
