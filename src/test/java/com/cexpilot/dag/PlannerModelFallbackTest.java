package com.cexpilot.dag;

import com.cexpilot.config.*;
import com.cexpilot.llm.*;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 双脚本模型与本地 Provider，校验模型切换、上下文和混合计费，不调用在线 API。 */
class PlannerModelFallbackTest {
    private final LlmConfig llmConfig = new LlmConfig();
    private final DagConfig config = new DagConfig();
    private final List<TraceEvent> events = new ArrayList<>();

    PlannerModelFallbackTest() {
        llmConfig.getNormal().setModel("model-one");
        llmConfig.getNormal().setPriceInputPer1k(1);
        llmConfig.getNormal().setPriceOutputPer1k(2);
        llmConfig.getFlagship().setModel("model-two");
        llmConfig.getFlagship().setPriceInputPer1k(10);
        llmConfig.getFlagship().setPriceOutputPer1k(20);
    }

    private static class Model extends Script {
        final String model;
        Model(String model, String... outputs) { super(outputs); this.model = model; }
        @Override public JsonNode requestParameters(JsonNode format) {
            var out = JSON.createObjectNode().put("model", model).put("seed", model.equals("model-one") ? 1 : 2);
            out.set("response_format", format == null ? JSON.nullNode() : format);
            return out;
        }
        @Override public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            if ("TRANSPORT_ERROR".equals(outputs.peek())) {
                outputs.remove(); calls.add(List.copyOf(messages)); throw new IllegalStateException("模拟超时");
            }
            return super.chat(messages, tools);
        }
    }

    private DagPlanner planner(Model one, Model two) {
        var registry = registry();
        return new DagPlanner(one, two, registry, llmConfig, config, new PromptStore(LOADER),
                new PlanValidator(registry, config), new MetricCatalog(LOADER), catalogProviders());
    }

    @Test void Spring生产构造按Qualifier注入两套客户端() {
        var one = new Model("model-one","bad"); var two = new Model("model-two",envelope(scalarPlan()));
        var registry = registry();
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(LlmClientConfig.NORMAL_LLM_CLIENT,LlmClient.class,() -> one, definition -> definition.setPrimary(true));
            context.registerBean(LlmClientConfig.FLAGSHIP_LLM_CLIENT,LlmClient.class,() -> two);
            context.registerBean(LlmConfig.class,() -> llmConfig);
            context.registerBean(DagConfig.class,() -> config);
            context.registerBean(ToolRegistry.class,() -> registry);
            context.registerBean(PromptStore.class,() -> new PromptStore(LOADER));
            context.registerBean(PlanValidator.class,() -> new PlanValidator(registry,config));
            context.registerBean(MetricCatalog.class,() -> new MetricCatalog(LOADER));
            context.registerBean(MetricProviderRegistry.class,MetricTestSupport::catalogProviders);
            context.register(DagPlanner.class);
            context.refresh();
            var result = context.getBean(DagPlanner.class).plan("查询","","trace",events::add);
            assertTrue(result.plan().isPresent());
            assertEquals(1,one.calls.size()); assertEquals(1,two.calls.size());
        }
    }

    @Test void 首次成功不调用第二模型() {
        var one = new Model("model-one", envelope(scalarPlan()));
        var two = new Model("model-two");
        var result = planner(one,two).plan("查询", "上下文", "trace", events::add);
        assertTrue(result.plan().isPresent());
        assertEquals(1, result.llmCalls()); assertEquals(0, two.calls.size());
        assertEquals(0.02, LlmUsage.totalCost(result.llmUsages()), 1e-12);
    }

    @Test void JSON失败后第二模型收到完整上下文错误及原输出并使用自己的请求参数() {
        config.setPlannerResponseFormat("json_schema");
        var one = new Model("model-one", "bad-json");
        var two = new Model("model-two", envelope(scalarPlan()));
        var result = planner(one,two).plan("昨天成交额", "这里有原任务和历史候选", "trace", events::add);
        assertTrue(result.plan().isPresent()); assertEquals(2,result.llmCalls());
        assertEquals(1,one.calls.size()); assertEquals(1,two.calls.size());
        var repair = two.calls.get(0);
        assertEquals(one.calls.get(0).get(0),repair.get(0));
        assertEquals(one.calls.get(0).get(1),repair.get(1));
        assertTrue(repair.get(0).content().contains("这里有原任务和历史候选"));
        assertEquals("bad-json",repair.get(2).content());
        assertTrue(repair.get(3).content().contains("解析失败"));
        assertEquals(one.format, two.format);
        var calls = events.stream().filter(e -> e.eventType().equals("LLM_CALL")).toList();
        assertEquals("model-one",json(calls.get(0).inputJson()).path("model").asText());
        assertEquals("model-two",json(calls.get(1).inputJson()).path("model").asText());
        assertEquals("flagship",json(calls.get(1).inputJson()).path("model_role").asText());
        assertEquals(2,json(calls.get(1).inputJson()).path("seed").asInt());
        assertEquals(0.2,json(calls.get(1).outputJson()).path("estimated_cost").asDouble(),1e-12);
        assertEquals(20,result.promptTokens()); assertEquals(10,result.completionTokens());
        assertEquals(0.22,LlmUsage.totalCost(result.llmUsages()),1e-12);
        var attempts=events.stream().filter(e -> e.eventType().equals("PLANNER_ATTEMPT")).toList();
        assertEquals(2,attempts.size());
        assertEquals("FAILED",json(attempts.get(0).outputJson()).path("validation_status").asText());
        assertEquals("PASSED",json(attempts.get(1).outputJson()).path("validation_status").asText());
        assertEquals("model-two",json(attempts.get(1).inputJson()).path("model").asText());
        assertEquals(result.plan().orElseThrow().logicalPlanJson(),json(attempts.get(1).outputJson()).path("logical_plan"));
    }

    @Test void 指标编译和引用校验失败也由模型二修复() {
        var unsupported = scalarPlan(); ((com.fasterxml.jackson.databind.node.ObjectNode)unsupported.at("/metrics/0"))
                .put("metric","made.up");
        var brokenReference = scalarPlan();
        calculation(brokenReference,"c","difference","{\"left\":\"{{missing.value}}\",\"right\":\"1\"}");
        for (JsonNode invalid : List.of(unsupported,brokenReference)) {
            var one = new Model("model-one",envelope(invalid));
            var two = new Model("model-two",envelope(scalarPlan()));
            var result = planner(one,two).plan("查询","","trace",events::add);
            assertTrue(result.plan().isPresent(),result.lastError());
            assertEquals(1,one.calls.size()); assertEquals(1,two.calls.size());
            assertTrue(two.calls.get(0).get(3).content().contains("错误明细"));
        }
    }

    @Test void 修复耗尽仍保持原上限且后续均使用模型二() {
        var one = new Model("model-one","bad");
        var two = new Model("model-two","bad","bad","不应调用");
        var result = planner(one,two).plan("查询","","trace",events::add);
        assertTrue(result.plan().isEmpty()); assertNotNull(result.lastError());
        assertEquals(3,result.llmCalls()); assertEquals(1,one.calls.size()); assertEquals(2,two.calls.size());
        assertEquals(0.42,LlmUsage.totalCost(result.llmUsages()),1e-12);
        assertEquals(6,two.calls.get(1).size());
    }

    @Test void 零次修复配置不会调用第二模型() {
        config.setPlannerMaxRetries(0);
        var one = new Model("model-one","bad"); var two = new Model("model-two",envelope(scalarPlan()));
        var result = planner(one,two).plan("查询","","trace",events::add);
        assertEquals(1,result.llmCalls()); assertTrue(two.calls.isEmpty());
    }

    @Test void 正常澄清出域和能力说明不是模型失败不切换() {
        for (String reply : List.of(
                "{\"in_domain\":false,\"reply\":\"不支持\",\"plan\":null}",
                "{\"in_domain\":true,\"needs_clarification\":true,\"clarify\":\"哪家交易所？\",\"plan\":null}",
                "{\"in_domain\":true,\"reply\":\"该能力未接入\",\"plan\":null}")) {
            var one = new Model("model-one",reply); var two = new Model("model-two");
            var result = planner(one,two).plan("查询","","trace",events::add);
            assertNull(result.lastError()); assertEquals(1,result.llmCalls()); assertTrue(two.calls.isEmpty());
        }
    }

    @Test void 首次传输错误不作为规划校验失败切换模型() {
        var one = new Model("model-one","TRANSPORT_ERROR"); var two = new Model("model-two");
        var failure = assertThrows(DagPlanner.PlanningFailure.class,
                () -> planner(one,two).plan("查询","","trace",events::add));
        assertEquals(1,failure.llmCalls); assertTrue(two.calls.isEmpty());
        assertNull(LlmUsage.totalCost(failure.llmUsages));
    }

    @Test void 第二模型传输失败携带先前使用量且不回切模型一() {
        var one = new Model("model-one","bad"); var two = new Model("model-two","TRANSPORT_ERROR");
        var failure = assertThrows(DagPlanner.PlanningFailure.class,
                () -> planner(one,two).plan("查询","","trace",events::add));
        assertEquals(2,failure.llmCalls); assertEquals(2,failure.llmUsages.size());
        assertEquals(10,LlmUsage.knownPromptTokens(failure.llmUsages));
        assertEquals(0.02,failure.llmUsages.get(0).estimatedCost(),1e-12);
        assertNull(failure.llmUsages.get(1).estimatedCost()); assertNull(LlmUsage.totalCost(failure.llmUsages));
        assertEquals("model-two",json(events.stream().filter(e -> e.eventType().equals("LLM_CALL") && e.error()!=null)
                .findFirst().orElseThrow().inputJson()).path("model").asText());
    }

    @Test void 历史事实修复仍只尝试一次不因切换模型绕过保护() {
        var metric = MetricTestSupport.metric("m","price.last","snapshot","binance");
        metric.remove("time"); metric.remove("interval");
        metric.put("reference_mode","pinned_result").put("evidence_ref","missing");
        String output = envelope(plan(metric));
        var one = new Model("model-one",output); var two = new Model("model-two",output,output);
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var result = planner(one,two).plan("刚才那个价","","trace",events::add,null,
                new RequestContext(ZoneOffset.ofHours(8),Instant.parse("2026-10-14T00:00:00Z")),
                new com.cexpilot.evidence.EvidenceBindingContext("conv",List.of(),store));
        assertTrue(result.needsClarification()); assertTrue(result.plan().isEmpty());
        assertEquals(2,result.llmCalls()); assertEquals(1,two.calls.size());
        var attempt=events.stream().filter(e -> e.eventType().equals("PLANNER_ATTEMPT")).reduce((a,b) -> b).orElseThrow();
        assertEquals("FAILED",json(attempt.outputJson()).path("validation_status").asText());
        assertFalse(json(attempt.outputJson()).has("logical_plan"));
    }

    @Test void 第二模型修复后Answer回到模型一并汇总各模型费用() {
        var one = new Model("model-one","bad","最终答案"); var two = new Model("model-two",envelope(scalarPlan()));
        var executor = new DagExecutor(registry(),config,providers());
        try {
            var runtime = new DagRuntime(one,planner(one,two),executor,new PromptStore(LOADER),
                    Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC));
            var result = runtime.execute("查询","","trace",events::add);
            assertEquals("最终答案",result.answer());
            assertEquals(2,result.plannerLlmCalls()); assertEquals(1,result.answerLlmCalls());
            assertEquals(2,one.calls.size()); assertEquals(1,two.calls.size());
            assertEquals(List.of("model-one","model-two","model-one"),result.llmUsages().stream().map(LlmUsage::model).toList());
            assertEquals(0.24,LlmUsage.totalCost(result.llmUsages()),1e-12);
            var answerEvent = events.stream().filter(e -> e.eventType().equals("LLM_CALL") && e.name().equals("answer"))
                    .findFirst().orElseThrow();
            assertEquals("model-one",json(answerEvent.inputJson()).path("model").asText());
        } finally { executor.shutdown(); }
    }

    @Test void 澄清路径和修复耗尽也保留全部模型使用量() {
        for (String[] repairs : List.of(
                new String[]{"{\"in_domain\":true,\"needs_clarification\":true,\"clarify\":\"哪个币种？\",\"plan\":null}"},
                new String[]{"bad","bad"})) {
            var one = new Model("model-one","bad"); var two = new Model("model-two",repairs);
            var executor = new DagExecutor(registry(),config,providers());
            try {
                var result = new DagRuntime(one,planner(one,two),executor,new PromptStore(LOADER),Clock.systemUTC())
                        .execute("查询","","trace",events::add);
                assertEquals(0,result.answerLlmCalls());
                assertEquals(result.plannerLlmCalls(),result.llmUsages().size());
                assertEquals(repairs.length==1 ? 0.22 : 0.42,LlmUsage.totalCost(result.llmUsages()),1e-12);
            } finally { executor.shutdown(); }
        }
    }
}
