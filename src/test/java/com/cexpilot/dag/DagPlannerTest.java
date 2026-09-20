package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.intent.IntentRegistry;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.ToolSpec;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.ToolSchemas;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DagPlanner 合并调用：领域判断 / intent 归类（hint）/ Plan 生成一次完成。
 * 覆盖：出域直接接受不 repair、plan=null 有意不规划不 repair、首轮合法、
 * repair 后合法、重试耗尽返回 empty、编造 intent 名归一为 UNKNOWN。
 */
class DagPlannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> script = new ArrayDeque<>();
        final List<List<ChatMessage>> seenMessages = new ArrayList<>();

        FakeLlmClient(ChatResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            seenMessages.add(List.copyOf(messages));
            if (script.isEmpty()) {
                return new ChatResponse("not json at all", List.of(), 1, 1);
            }
            return script.poll();
        }
    }

    static class StubTool implements com.cexpilot.runtime.TestTools.TestTool {
        @Override
        public String name() {
            return "echo_tool";
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public JsonNode inputSchema() {
            return MAPPER.createObjectNode();
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            return ToolResult.success(MAPPER.createObjectNode());
        }
    }

    static class ListSink implements TraceSink {
        final List<TraceEvent> events = new ArrayList<>();

        @Override
        public void record(TraceEvent event) {
            events.add(event);
        }
    }

    private static DagPlanner planner(LlmClient llm, DagConfig dagConfig) {
        ToolRegistry registry = com.cexpilot.runtime.TestTools.registry(List.of(new StubTool()));
        return new DagPlanner(llm, registry, new IntentRegistry(new DefaultResourceLoader()),
                new LlmConfig(), dagConfig,
                new PromptStore(new DefaultResourceLoader()),
                new PlanValidator(registry, dagConfig));
    }

    private static ChatResponse respond(String content) {
        return new ChatResponse(content, List.of(), 10, 5);
    }

    private static final String VALID_ENVELOPE = """
            {"in_domain": true, "intent": "MARKET_LOOKUP", "reply": null,
             "plan": {"nodes": [{"id": "n1", "tool": "echo_tool", "args": {}, "depends_on": []}]}}
            """;

    @Test
    void validPlanOnFirstAttempt() {
        FakeLlmClient llm = new FakeLlmClient(respond(VALID_ENVELOPE));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-1", sink);

        assertTrue(outcome.inDomain());
        assertEquals("MARKET_LOOKUP", outcome.intent());
        assertTrue(outcome.plan().isPresent());
        assertEquals(1, outcome.plan().get().nodes().size());
        assertEquals(10, outcome.promptTokens());
        assertEquals(5, outcome.completionTokens());
        // trace：1 次 planner LLM_CALL + 1 条 PLAN（校验通过，无 error）
        assertEquals(2, sink.events.size());
        assertEquals("LLM_CALL", sink.events.get(0).eventType());
        assertEquals("dag_planner", sink.events.get(0).name());
        assertEquals("PLAN", sink.events.get(1).eventType());
        assertNull(sink.events.get(1).error());
        assertEquals(1, llm.seenMessages.size());
    }

    @Test
    void outOfDomainAcceptedWithoutRepair() {
        FakeLlmClient llm = new FakeLlmClient(respond(
                "{\"in_domain\": false, \"intent\": \"UNKNOWN\", \"reply\": \"我只支持加密货币问题\", \"plan\": null}"));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("写首诗", "", "trace-2", sink);

        assertFalse(outcome.inDomain());
        assertNull(outcome.intent());
        assertEquals("我只支持加密货币问题", outcome.reply());
        assertTrue(outcome.plan().isEmpty());
        // 不 repair：只调 1 次 LLM
        assertEquals(1, llm.seenMessages.size());
    }

    @Test
    void nullPlanAcceptedAsIntentionalSkip() {
        FakeLlmClient llm = new FakeLlmClient(respond(
                "{\"in_domain\": true, \"intent\": \"UNKNOWN\", \"reply\": \"缺少链上持仓数据\", \"plan\": null}"));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("这个问题工具不够", "", "trace-3", sink);

        assertTrue(outcome.inDomain());
        assertEquals("UNKNOWN", outcome.intent());
        assertEquals("缺少链上持仓数据", outcome.reply());
        assertTrue(outcome.plan().isEmpty());
        assertNull(outcome.lastError());
        // 有意不规划：不 repair，只调 1 次 LLM
        assertEquals(1, llm.seenMessages.size());
    }

    @Test
    void fabricatedIntentFallsBackToUnknown() {
        FakeLlmClient llm = new FakeLlmClient(respond(
                "{\"in_domain\": true, \"intent\": \"MADE_UP\", \"reply\": null, \"plan\": null}"));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-4", sink);

        assertEquals("UNKNOWN", outcome.intent());
    }

    @Test
    void invalidPlanRepairedWithErrorDetails() {
        FakeLlmClient llm = new FakeLlmClient(
                respond("{\"in_domain\": true, \"intent\": \"UNKNOWN\", \"reply\": null,"
                        + " \"plan\": {\"nodes\": [{\"id\": \"n1\", \"tool\": \"ghost\", \"args\": {}}]}}"),
                respond(VALID_ENVELOPE));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-5", sink);

        assertTrue(outcome.plan().isPresent());
        assertEquals(2, llm.seenMessages.size());
        // repair 消息携带校验错误明细 + 针对工具名错误的格式提示
        List<ChatMessage> secondCall = llm.seenMessages.get(1);
        ChatMessage repair = secondCall.get(secondCall.size() - 1);
        assertEquals("user", repair.role());
        assertTrue(repair.content().contains("未注册的工具"));
        assertTrue(repair.content().contains("tool 字段只能填工具名本身"));
        assertTrue(repair.content().contains("echo_tool"));
        // token 累计两轮
        assertEquals(20, outcome.promptTokens());
        // trace：2 次 LLM_CALL + 2 条 PLAN（第一条带 error）
        long planEvents = sink.events.stream().filter(e -> e.eventType().equals("PLAN")).count();
        assertEquals(2, planEvents);
        assertTrue(sink.events.stream()
                .filter(e -> e.eventType().equals("PLAN"))
                .anyMatch(e -> e.error() != null && e.error().contains("未注册的工具")));
    }

    @Test
    void toolListIsCompactWithoutFullJsonSchema() {
        StubTool schemaTool = new StubTool() {
            @Override
            public JsonNode inputSchema() {
                return ToolSchemas.parse("""
                        {"type": "object", "properties": {
                          "symbol": {"type": "string", "description": "币种基础代码"},
                          "window": {"type": "string", "enum": ["1h", "4h", "24h"], "description": "时间窗口，默认 1h"}
                        }, "required": ["symbol"]}
                        """);
            }
        };
        ToolRegistry registry = com.cexpilot.runtime.TestTools.registry(List.of(schemaTool));
        FakeLlmClient llm = new FakeLlmClient(respond(VALID_ENVELOPE));
        DagPlanner planner = new DagPlanner(llm, registry,
                new IntentRegistry(new DefaultResourceLoader()), new LlmConfig(), new DagConfig(),
                new PromptStore(new DefaultResourceLoader()),
                new PlanValidator(registry, new DagConfig()));

        planner.plan("问题", "", "trace-7", new ListSink());

        String system = llm.seenMessages.get(0).get(0).content();
        // 紧凑格式：参数名 + 必填 * + 枚举 + 一句说明
        assertTrue(system.contains("echo_tool：测试工具"));
        assertTrue(system.contains("symbol*(string, 币种基础代码)"));
        assertTrue(system.contains("window(string, 1h|4h|24h, 时间窗口，默认 1h)"));
        // 不下发完整 JSON Schema
        assertFalse(system.contains("\"type\": \"object\""));
        assertFalse(system.contains("properties"));
    }

    @Test
    void retriesExhaustedReturnsEmpty() {
        DagConfig dagConfig = new DagConfig();
        dagConfig.setPlannerMaxRetries(1);
        FakeLlmClient llm = new FakeLlmClient(); // 脚本为空，永远返回非 JSON
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, dagConfig)
                .plan("问题", "", "trace-6", sink);

        assertTrue(outcome.inDomain());
        assertEquals("UNKNOWN", outcome.intent());
        assertTrue(outcome.plan().isEmpty());
        assertEquals(2, llm.seenMessages.size()); // 首次 + 1 次重试
        assertTrue(outcome.lastError().contains("解析失败"));
    }

    @Test
    void bareNodesArraySalvagedAsPlan() {
        // 模型丢了信封直接输出 nodes 裸数组：判断正确、包装缺失，应抢救成功且不重试
        FakeLlmClient llm = new FakeLlmClient(respond(
                "[{\"id\": \"n1\", \"tool\": \"echo_tool\", \"args\": {}, \"depends_on\": []}]"));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-8", sink);

        assertTrue(outcome.inDomain());
        assertEquals("UNKNOWN", outcome.intent());
        assertTrue(outcome.plan().isPresent());
        assertEquals(1, outcome.plan().get().nodes().size());
        assertEquals("echo_tool", outcome.plan().get().nodes().get(0).tool());
        assertEquals(1, llm.seenMessages.size());
        // PLAN trace 落的是抢救出的规范 plan，无 error
        assertEquals("PLAN", sink.events.get(1).eventType());
        assertNull(sink.events.get(1).error());
        assertTrue(sink.events.get(1).outputJson().contains("\"nodes\""));
    }

    @Test
    void objectWithPlanButNoEnvelopeSalvaged() {
        FakeLlmClient llm = new FakeLlmClient(respond(
                "{\"plan\": {\"nodes\": [{\"id\": \"n1\", \"tool\": \"echo_tool\", \"args\": {}}]}}"));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-9", sink);

        assertTrue(outcome.inDomain());
        assertTrue(outcome.plan().isPresent());
        assertEquals(1, llm.seenMessages.size());
    }

    @Test
    void unsalvageableMissingEnvelopeTriggersRepair() {
        // 既无信封也无可抢救的 plan → 视为格式错误走 repair，而不是误判出域
        FakeLlmClient llm = new FakeLlmClient(
                respond("{\"result\": \"some text\"}"),
                respond(VALID_ENVELOPE));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-10", sink);

        assertTrue(outcome.plan().isPresent());
        assertEquals(2, llm.seenMessages.size());
        List<ChatMessage> secondCall = llm.seenMessages.get(1);
        ChatMessage repair = secondCall.get(secondCall.size() - 1);
        assertTrue(repair.content().contains("缺少信封"));
        assertTrue(sink.events.stream()
                .filter(e -> e.eventType().equals("PLAN"))
                .anyMatch(e -> e.error() != null && e.error().contains("缺少信封")));
    }

    @Test
    void emptyArrayNotSalvaged() {
        // 空数组没有可执行的节点，不能抢救，应走 repair
        FakeLlmClient llm = new FakeLlmClient(
                respond("[]"),
                respond(VALID_ENVELOPE));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-11", sink);

        assertTrue(outcome.plan().isPresent());
        assertEquals(2, llm.seenMessages.size());
    }

    @Test
    void emptyNodesWithReplyAcceptedAsIntentionalSkip() {
        // 模型用 plan:{"nodes":[]} + reply 表达追问：等价于 plan=null，直接接受不 repair
        FakeLlmClient llm = new FakeLlmClient(respond(
                "{\"in_domain\": true, \"intent\": \"MARKET_LOOKUP\","
                        + " \"reply\": \"请提供需要查询的币种代码\", \"plan\": {\"nodes\": []}}"));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("现在盘口压单重不重", "", "trace-13", sink);

        assertTrue(outcome.inDomain());
        assertEquals("MARKET_LOOKUP", outcome.intent());
        assertEquals("请提供需要查询的币种代码", outcome.reply());
        assertTrue(outcome.plan().isEmpty());
        assertNull(outcome.lastError());
        assertEquals(1, llm.seenMessages.size());
        assertNull(sink.events.get(1).error());
    }

    @Test
    void emptyNodesWithoutReplyTriggersRepair() {
        // 空 nodes 且没有 reply：模型什么都没表达，仍按校验失败走 repair
        FakeLlmClient llm = new FakeLlmClient(
                respond("{\"in_domain\": true, \"intent\": \"MARKET_LOOKUP\","
                        + " \"reply\": null, \"plan\": {\"nodes\": []}}"),
                respond(VALID_ENVELOPE));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-14", sink);

        assertTrue(outcome.plan().isPresent());
        assertEquals(2, llm.seenMessages.size());
        assertTrue(sink.events.stream()
                .filter(e -> e.eventType().equals("PLAN"))
                .anyMatch(e -> e.error() != null && e.error().contains("plan 不包含任何节点")));
    }

    @Test
    void longReasoningReplyWithoutPlanTriggersRepair() {
        // 模型把推理过程倒进 reply 且没给 plan：协议误用，必须 repair 而不是透传
        FakeLlmClient llm = new FakeLlmClient(
                respond("{\"in_domain\": true, \"intent\": \"MARKET_LOOKUP\","
                        + " \"reply\": \"" + "推理".repeat(60) + "\"}"),
                respond(VALID_ENVELOPE));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-15", sink);

        assertTrue(outcome.plan().isPresent());
        assertEquals(2, llm.seenMessages.size());
        List<ChatMessage> secondCall = llm.seenMessages.get(1);
        assertTrue(secondCall.get(secondCall.size() - 1).content().contains("推理过程"));
    }

    @Test
    void longReasoningReplyWithEmptyNodesTriggersRepair() {
        // 空 nodes + 超长 reply 同理：不能透传
        FakeLlmClient llm = new FakeLlmClient(
                respond("{\"in_domain\": true, \"intent\": \"MARKET_LOOKUP\","
                        + " \"reply\": \"" + "推理".repeat(60) + "\", \"plan\": {\"nodes\": []}}"),
                respond(VALID_ENVELOPE));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-16", sink);

        assertTrue(outcome.plan().isPresent());
        assertEquals(2, llm.seenMessages.size());
    }

    @Test
    void longReasoningReplyDroppedWhenPlanValid() {
        // plan 合法但 reply 是推理 dump：直接丢弃 reply，不为它浪费 repair
        FakeLlmClient llm = new FakeLlmClient(respond(
                "{\"in_domain\": true, \"intent\": \"MARKET_LOOKUP\","
                        + " \"reply\": \"" + "推理".repeat(60) + "\","
                        + " \"plan\": {\"nodes\": [{\"id\": \"n1\", \"tool\": \"echo_tool\", \"args\": {}}]}}"));
        ListSink sink = new ListSink();

        DagPlanner.PlanOutcome outcome = planner(llm, new DagConfig())
                .plan("问题", "", "trace-17", sink);

        assertTrue(outcome.plan().isPresent());
        assertNull(outcome.reply());
        assertNull(outcome.lastError());
        assertEquals(1, llm.seenMessages.size());
    }

    @Test
    void plannerResponseFormatBuildsJsonSchema() {
        DagConfig dagConfig = new DagConfig();
        dagConfig.setPlannerResponseFormat("json_schema");
        FakeLlmClient llm = new FakeLlmClient(respond(VALID_ENVELOPE));

        DagPlanner.PlanOutcome outcome = planner(llm, dagConfig)
                .plan("问题", "", "trace-12", new ListSink());

        assertTrue(outcome.plan().isPresent());
    }
}
