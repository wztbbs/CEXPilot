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
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DagRuntime 端到端：合并规划（领域判断 + intent 归类 + plan）→ DAG 执行 → answer；
 * 以及出域短路、plan=null 与 repair 耗尽两条降级路径。
 */
class DagRuntimeTest {

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
                return new ChatResponse("not json", List.of(), 1, 1);
            }
            return script.poll();
        }
    }

    static class EchoTool implements com.cexpilot.runtime.TestTools.TestTool {
        private final String name;
        private final JsonNode data;
        int calls = 0;

        EchoTool(String name) {
            this(name, null);
        }

        EchoTool(String name, JsonNode data) {
            this.name = name;
            this.data = data;
        }

        @Override
        public String name() {
            return name;
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
            calls++;
            return ToolResult.success(data != null ? data : MAPPER.createObjectNode().put("from", name));
        }
    }

    static class ListSink implements TraceSink {
        final List<TraceEvent> events = new ArrayList<>();

        @Override
        public void record(TraceEvent event) {
            events.add(event);
        }
    }

    private static DagRuntime runtime(FakeLlmClient llm, List<AgentTool> tools, DagConfig dagConfig) {
        return runtime(llm, tools, dagConfig, Clock.systemUTC());
    }

    private static DagRuntime runtime(FakeLlmClient llm, List<AgentTool> tools, DagConfig dagConfig, Clock clock) {
        ToolRegistry registry = com.cexpilot.runtime.TestTools.registry(tools);
        PromptStore prompts = new PromptStore(new DefaultResourceLoader());
        IntentRegistry intentRegistry = new IntentRegistry(new DefaultResourceLoader());
        DagPlanner planner = new DagPlanner(llm, registry, intentRegistry, new LlmConfig(), dagConfig,
                prompts, new PlanValidator(registry, dagConfig));
        return new DagRuntime(llm, planner, new DagExecutor(registry, dagConfig), prompts, intentRegistry, clock);
    }

    private static FakeLlmClient timeQueryLlm(String time) {
        return new FakeLlmClient(new ChatResponse("""
                {"in_domain":true,"intent":"MARKET_ANALYSIS","reply":null,
                 "plan":{"nodes":[{"id":"n1","tool":"get_klines","args":{"time":%s},"depends_on":[]}]}}
                """.formatted(time), List.of(), 1, 1), new ChatResponse("回答", List.of(), 1, 1));
    }

    private static EchoTool timeQueryTool(Clock clock) {
        return new EchoTool("get_klines") {
            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                var range = new TimeRangeResolver(clock).resolve(ctx.timezone(),
                        TimeSpecParser.parse(args.path("time")), ctx.requestTime());
                ObjectNode data = MAPPER.createObjectNode();
                data.put("request_time", ctx.requestTime().toString());
                data.put("request_timezone", ctx.timezone().getId());
                data.putObject("requested_range")
                        .put("timezone", range.timezone().getId())
                        .put("start_inclusive", range.startInclusive().toString())
                        .put("end_exclusive", range.endExclusive().toString());
                return ToolResult.success(data);
            }
        };
    }

    private static JsonNode answerTimeContext(FakeLlmClient llm) throws Exception {
        String system = llm.seenMessages.get(1).get(0).content();
        return MAPPER.readTree(system.substring(system.indexOf("<TIME_CONTEXT>") + "<TIME_CONTEXT>".length(),
                system.indexOf("</TIME_CONTEXT>")));
    }

    private static final String YESTERDAY_AFTERNOON = """
            {"type":"calendar_period","unit":"day","offset":-1,"segment":"afternoon","extent":"full_period"}
            """;

    @Test
    void answerAndToolsUseSameRequestTimeAndUserTimezone() throws Exception {
        Instant requestTime = Instant.parse("2026-09-24T16:30:00Z");
        // Answer 执行时已是另一天、服务器时区也不同；不得替换入口传入的请求基准。
        Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneId.of("Europe/London"));
        for (String zone : List.of("Asia/Shanghai", "America/New_York")) {
            var llm = timeQueryLlm(YESTERDAY_AFTERNOON);
            var sink = new ListSink();
            var result = runtime(llm, List.of(timeQueryTool(clock)), new DagConfig(), clock).execute(
                    "昨天下午的走势？", "旧对话的日期不是当前日期", "time-context", sink, null,
                    new RequestContext(ZoneId.of(zone), requestTime));
            JsonNode time = answerTimeContext(llm);
            assertEquals(requestTime.toString(), time.path("request_time_utc").asText());
            assertEquals(zone, time.path("timezone").asText());
            assertEquals("request", time.path("timezone_source").asText());
            boolean shanghai = zone.equals("Asia/Shanghai");
            assertEquals(shanghai ? "2026-09-25" : "2026-09-24", time.path("current_date").asText());
            assertEquals(shanghai ? "2026-09-25T00:30:00+08:00" : "2026-09-24T12:30:00-04:00",
                    time.path("request_time_local").asText());
            JsonNode data = result.evidence().get(0).path("data");
            assertEquals(requestTime.toString(), data.path("request_time").asText());
            assertEquals(shanghai ? "2026-09-24T04:00:00Z" : "2026-09-23T16:00:00Z",
                    data.at("/requested_range/start_inclusive").asText());
            var answerEvent = sink.events.stream().filter(e -> e.name().equals("answer")).findFirst().orElseThrow();
            assertEquals(time, MAPPER.readTree(answerEvent.inputJson()).path("time_context"));
        }
    }

    @Test
    void explicitQueryTimezoneIsNotOverwrittenByRequestTimezone() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T16:30:00Z"), ZoneOffset.UTC);
        ObjectNode spec = (ObjectNode) MAPPER.readTree(YESTERDAY_AFTERNOON);
        spec.put("timezone", "America/New_York");
        var llm = timeQueryLlm(spec.toString());
        runtime(llm, List.of(timeQueryTool(clock)), new DagConfig(), clock).execute(
                "纽约时间昨天下午走势？", "", "query-zone", new ListSink(), null,
                new RequestContext(ZoneId.of("Asia/Shanghai"), clock.instant()));
        assertEquals("2026-09-25", answerTimeContext(llm).path("current_date").asText());
        String answerInput = llm.seenMessages.get(1).get(1).content();
        assertTrue(answerInput.contains("America/New_York"));
        assertTrue(answerInput.contains("2026-09-23T16:00:00Z"));
        String system = llm.seenMessages.get(1).get(0).content();
        assertTrue(system.contains("不得用请求默认时区覆盖它"));
        assertTrue(system.contains("不凭训练知识、历史对话或猜测确定今天几号"));
        assertTrue(system.contains("range_complete=false 时不得声称完整覆盖"));
    }

    @Test
    void missingContextIsFrozenBeforePlanningAcrossMidnightForStreamingAnswer() throws Exception {
        Clock clock = mock(Clock.class);
        Instant beforeMidnight = Instant.parse("2026-09-25T15:59:59Z");
        when(clock.instant()).thenReturn(beforeMidnight);
        var llm = new FakeLlmClient(new ChatResponse("""
                {"in_domain":true,"intent":"MARKET_ANALYSIS","reply":null,
                 "plan":{"nodes":[{"id":"n1","tool":"get_klines","args":{"time":%s},"depends_on":[]}]}}
                """.formatted(YESTERDAY_AFTERNOON), List.of(), 1, 1),
                new ChatResponse("回答", List.of(), 1, 1)) {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                if (seenMessages.isEmpty()) {
                    // 在 Planner 开始前已固定基准；模拟规划期间跨过 UTC+8 午夜。
                    verify(clock, times(1)).instant();
                    when(clock.instant()).thenReturn(Instant.parse("2026-09-25T16:00:01Z"));
                }
                return super.chat(messages, tools);
            }
        };
        List<String> deltas = new ArrayList<>();
        var result = runtime(llm, List.of(timeQueryTool(clock)), new DagConfig(), clock).execute(
                "昨天下午走势？", "", "default-time", new ListSink(), deltas::add);
        JsonNode time = answerTimeContext(llm);
        assertEquals("2026-09-25", time.path("current_date").asText());
        assertEquals("+08:00", time.path("timezone").asText());
        assertEquals("default", time.path("timezone_source").asText());
        assertEquals(beforeMidnight.toString(), time.path("request_time_utc").asText());
        assertEquals(beforeMidnight.toString(), result.evidence().get(0).at("/data/request_time").asText());
        assertEquals("2026-09-24T04:00:00Z",
                result.evidence().get(0).at("/data/requested_range/start_inclusive").asText());
        assertEquals(List.of("回答"), deltas);
        verify(clock, times(1)).instant();
    }

    @Test
    void endToEndPlanExecuteAnswer() {
        EchoTool toolA = new EchoTool("tool_a");
        EchoTool toolB = new EchoTool("tool_b");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true,  "intent": "MARKET_LOOKUP", "reply": null,
                         "plan": {"nodes": [
                           {"id": "n1", "tool": "tool_a", "args": {}, "depends_on": []},
                           {"id": "n2", "tool": "tool_b", "args": {"x": "{{n1.data.from}}"}, "depends_on": ["n1"]}
                         ]}}
                        """, List.of(), 10, 5),
                new ChatResponse("最终回答", List.of(), 20, 8));
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(toolA, toolB), new DagConfig())
                .execute("BTC 怎么了？", "", "trace-1", sink);

        assertEquals("最终回答", result.answer());
        assertEquals("MARKET_LOOKUP", result.intent());
        assertEquals(1, toolA.calls);
        assertEquals(1, toolB.calls);
        assertEquals(2, result.toolCallCount());
        assertEquals(2, result.steps()); // 两层 DAG
        assertEquals(30, result.promptTokens());
        assertEquals(13, result.completionTokens());

        // evidence 含两个节点（含 nodeId 与工具名）
        String evidence = result.evidence().toString();
        assertTrue(evidence.contains("tool_a"));
        assertTrue(evidence.contains("\"node_id\":\"n2\""));

        // trace：1 次 dag_planner LLM_CALL + 1 条 PLAN + 2 条 TOOL_CALL + 1 次 answer LLM_CALL
        assertEquals(5, sink.events.size());
        assertEquals("dag_planner", sink.events.get(0).name());
        assertEquals("PLAN", sink.events.get(1).eventType());
        assertEquals("TOOL_CALL", sink.events.get(2).eventType());
        assertEquals("TOOL_CALL", sink.events.get(3).eventType());
        assertEquals("LLM_CALL", sink.events.get(4).eventType());
        assertEquals("answer", sink.events.get(4).name());

        // 最终回答调用的 user 消息带上了完整 evidence
        List<ChatMessage> answerCall = llm.seenMessages.get(1);
        ChatMessage user = answerCall.get(answerCall.size() - 1);
        assertTrue(user.content().contains("BTC 怎么了？"));
        assertTrue(user.content().contains("tool_a"));
        // 所有意图使用相同的事实约束，不强制固定字数
        assertTrue(answerCall.get(0).content().contains("事实性结论只能来自 FACTS"));
        assertFalse(answerCall.get(0).content().contains("150"));
        // 命中 MARKET_LOOKUP：该意图的 evidence_policy.rules 注入回答 prompt
        assertTrue(answerCall.get(0).content().contains("本轮问题归类为 MARKET_LOOKUP"));
        assertTrue(answerCall.get(0).content().contains("不允许凭记忆报价"));
    }

    @Test
    void klinesHistoricalPlanPassesGuardsAndExecutes() {
        // planner → executor 接入：历史查询不再依赖全局时间分类或工具白名单
        EchoTool klines = new EchoTool("get_klines");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true,  "intent": "MARKET_LOOKUP", "reply": null,
                         "plan": {"nodes": [
                           {"id": "n1", "tool": "get_klines", "args": {"symbol": "BTC"}, "depends_on": []}
                         ]}}
                        """, List.of(), 10, 5),
                new ChatResponse("最终回答", List.of(), 20, 8));
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(klines), new DagConfig())
                .execute("BTC 昨天的 K 线", "", "trace-klines", sink);

        assertEquals(1, klines.calls);
        assertEquals(1, result.toolCallCount());
        assertEquals("最终回答", result.answer());
    }

    @Test
    void answerReceivesAll72CandlesWithColumnsWithoutDetailFlag() throws Exception {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("symbol", "BTC");
        data.put("complete", true);
        data.putArray("candles_columns").add("open_time").add("open").add("high").add("low").add("close");
        ArrayNode candles = data.putArray("candles");
        for (int i = 0; i < 72; i++) {
            candles.addArray().add(1_780_000_000_000L + i * 300_000L)
                    .add("60000").add("60200").add("59900").add("60100");
        }
        EchoTool klines = new EchoTool("get_klines", data);
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true, "intent": "MARKET_ANALYSIS", "reply": null,
                         "plan": {"nodes": [
                           {"id": "n1", "tool": "get_klines", "args": {}, "depends_on": []}
                         ]}}
                        """, List.of(), 10, 5),
                new ChatResponse("走势回答", List.of(), 20, 8));

        ExecutionResult result = runtime(llm, List.of(klines), new DagConfig())
                .execute("昨天下午 BTC 的走势怎么样？", "", "trace-candles", new ListSink());

        String content = llm.seenMessages.get(1).get(1).content();
        JsonNode facts = MAPPER.readTree(content.substring(
                content.indexOf("<FACTS>") + "<FACTS>".length(), content.indexOf("</FACTS>")));
        assertEquals(result.evidence(), facts);
        assertEquals(data, facts.get(0).path("data"));
        assertEquals(72, facts.get(0).path("data").path("candles").size());
        assertEquals(data.path("candles_columns"), facts.get(0).path("data").path("candles_columns"));
        assertFalse(content.contains("明细序列已省略"));
        assertTrue(llm.seenMessages.get(1).get(0).content().contains("用户要求分析时"));
    }

    @Test
    void exchangeComparisonUsesSameFactBoundaries() {
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true,  "intent": "EXCHANGE_COMPARE", "reply": null,
                         "plan": {"nodes": [
                           {"id": "n1", "tool": "tool_a", "args": {}, "depends_on": []}
                         ]}}
                        """, List.of(), 10, 5),
                new ChatResponse("对比回答", List.of(), 20, 8));
        ListSink sink = new ListSink();

        runtime(llm, List.of(tool), new DagConfig()).execute("两所价差？", "", "trace-6", sink);

        String system = llm.seenMessages.get(1).get(0).content();
        assertTrue(system.contains("事实性结论只能来自 FACTS"));
    }

    @Test
    void outOfDomainShortCircuitsWithoutToolsOrAnswerLlm() {
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("{\"in_domain\": false, \"intent\": \"UNKNOWN\","
                        + " \"reply\": \"我只支持加密货币问题\", \"plan\": null}", List.of(), 10, 5));
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(tool), new DagConfig())
                .execute("写首诗", "", "trace-2", sink);

        assertEquals("我只支持加密货币问题", result.answer());
        assertNull(result.intent());
        assertEquals(0, tool.calls);
        assertEquals(0, result.toolCallCount());
        assertEquals(0, result.steps());
        assertEquals(0, result.evidence().size());
        assertEquals(10, result.promptTokens());
        // 只调了 planner 一次 LLM（无 answer 调用、无工具调用）
        assertEquals(1, llm.seenMessages.size());
        assertTrue(sink.events.stream().noneMatch(e -> e.eventType().equals("TOOL_CALL")));
        assertTrue(sink.events.stream().noneMatch(e -> "answer".equals(e.name())));
    }

    @Test
    void nullPlanWithReplyPassesThroughDirectly() {
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("{\"in_domain\": true,  \"intent\": \"UNKNOWN\","
                        + " \"reply\": \"缺少链上持仓数据\", \"plan\": null}", List.of(), 10, 5));
        ListSink sink = new ListSink();
        List<String> deltas = new ArrayList<>();

        ExecutionResult result = runtime(llm, List.of(tool), new DagConfig())
                .execute("这个问题工具不够", "", "trace-3", sink, deltas::add);

        // reply 直接成为答案，不再走 answer LLM 泛化；流式场景一次性推全量
        assertEquals("缺少链上持仓数据", result.answer());
        assertEquals(List.of("缺少链上持仓数据"), deltas);
        assertEquals("UNKNOWN", result.intent());
        assertEquals(0, tool.calls);
        assertEquals(0, result.toolCallCount());
        assertEquals(0, result.evidence().size());
        assertEquals(10, result.promptTokens());
        assertEquals(1, llm.seenMessages.size());
        assertTrue(sink.events.stream().noneMatch(e -> "answer".equals(e.name())));
    }

    @Test
    void emptyNodesWithReplyPassesThroughDirectly() {
        // D10 场景：planner 追问"请提供币种代码"（nodes 为空），reply 必须透传给用户
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("{\"in_domain\": true,  \"intent\": \"MARKET_LOOKUP\","
                        + " \"reply\": \"请提供需要查询的币种代码（如 BTC、ETH 等）\","
                        + " \"plan\": {\"nodes\": []}}", List.of(), 10, 5));
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(tool), new DagConfig())
                .execute("现在盘口压单重不重", "", "trace-7", sink);

        assertEquals("请提供需要查询的币种代码（如 BTC、ETH 等）", result.answer());
        assertEquals("MARKET_LOOKUP", result.intent());
        assertEquals(0, tool.calls);
        assertEquals(1, llm.seenMessages.size());
    }

    @Test
    void plannerFailureRefusesWithoutAnswerLlm() {
        EchoTool tool = new EchoTool("tool_a");
        // planner 两轮都返回非 JSON，重试耗尽；不得进入第 3 次 Answer 调用
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("garbage", List.of(), 10, 5),
                new ChatResponse("still garbage", List.of(), 10, 5),
                new ChatResponse("缺少查询事实，无法确认", List.of(), 20, 8));
        DagConfig dagConfig = new DagConfig();
        dagConfig.setPlannerMaxRetries(1);
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(tool), dagConfig)
                .execute("BTC 怎么了？", "", "trace-4", sink);

        assertTrue(result.answer().contains("本次未执行查询"));
        assertEquals(2, llm.seenMessages.size());
        assertEquals("UNKNOWN", result.intent());
        assertEquals(0, tool.calls);
        assertEquals(0, result.toolCallCount());
        assertEquals(0, result.steps());
        assertEquals(0, result.evidence().size());
        assertEquals(20, result.promptTokens());

        // trace：只有两次 planner 调用和两条失败 PLAN
        long planErrors = sink.events.stream()
                .filter(e -> e.eventType().equals("PLAN") && e.error() != null).count();
        assertEquals(2, planErrors);
        assertTrue(sink.events.stream().noneMatch(e -> "answer".equals(e.name())));
        assertTrue(sink.events.stream().noneMatch(e -> e.eventType().equals("TOOL_CALL")));
    }
    @Test
    void requestedHistoryAndPartialQueryGapReachAnswer() {
        ObjectNode data = MAPPER.createObjectNode();
        data.putArray("rates").add(0.0001).add(0.0002);
        EchoTool tool = new EchoTool("get_funding_rate_history", data);
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain":true,"intent":"MARKET_LOOKUP","reply":"仅有最近两期数据",
                         "plan":{"nodes":[{"id":"n1","tool":"get_funding_rate_history","args":{},
                         "depends_on":[]}]}}
                        """, List.of(), 10, 5),
                new ChatResponse("最近两期费率", List.of(), 20, 8));
        runtime(llm, List.of(tool), new DagConfig()).execute("列出近期可获取的费率样本", "", "details", new ListSink());
        String answerInput = llm.seenMessages.get(1).get(1).content();
        assertTrue(answerInput.contains("\"rates\":[1.0E-4,2.0E-4]")
                || answerInput.contains("\"rates\":[0.0001,0.0002]"));
        assertTrue(answerInput.contains("仅有最近两期数据"));
        assertFalse(answerInput.contains("明细序列已省略"));
    }

    @Test
    void streamingAnswerFallsBackToOneShotDelta() {
        // FakeLlmClient 默认 chatStream：非流式调用后一次性回调完整内容
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true,  "intent": "MARKET_LOOKUP", "reply": null,
                         "plan": {"nodes": [{"id": "n1", "tool": "tool_a", "args": {}, "depends_on": []}]}}
                        """, List.of(), 10, 5),
                new ChatResponse("最终回答", List.of(), 20, 8));
        List<String> deltas = new ArrayList<>();

        ExecutionResult result = runtime(llm, List.of(tool), new DagConfig())
                .execute("BTC 怎么了？", "", "trace-stream", new ListSink(), deltas::add);

        assertEquals("最终回答", result.answer());
        assertEquals(List.of("最终回答"), deltas);
        assertEquals(30, result.promptTokens());
    }

    @Test
    void streamingAnswerRoutesThroughChatStream() {
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true,  "intent": "MARKET_LOOKUP", "reply": null,
                         "plan": {"nodes": [{"id": "n1", "tool": "tool_a", "args": {}, "depends_on": []}]}}
                        """, List.of(), 10, 5)) {
            @Override
            public ChatResponse chatStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                           java.util.function.Consumer<String> onDelta) {
                seenMessages.add(List.copyOf(messages));
                onDelta.accept("最终");
                onDelta.accept("回答");
                return new ChatResponse("最终回答", List.of(), 20, 8);
            }
        };
        List<String> deltas = new ArrayList<>();

        ExecutionResult result = runtime(llm, List.of(tool), new DagConfig())
                .execute("BTC 怎么了？", "", "trace-stream2", new ListSink(), deltas::add);

        assertEquals("最终回答", result.answer());
        assertEquals(List.of("最终", "回答"), deltas);
        // answer 走了 chatStream：第二次调用即 answer 阶段
        assertEquals(2, llm.seenMessages.size());
    }
    @Test
    void missingCoreResultUsesPlannerExplanationWithoutToolsOrAnswerIncludingStreaming() {
        for (String question : List.of("对比币安和 OKX 最近一小时走势，哪个涨得更多？",
                "币安 BTC 成交量同比", "BTC 本周对上周涨跌")) {
            EchoTool ticker = new EchoTool("get_ticker");
            String missing = "暂时无法产出所要求的比较结果，目前只能分别查询指标。";
            ObjectNode envelope = MAPPER.createObjectNode().put("in_domain", true)
                    .put("intent", "EXCHANGE_COMPARE").putNull("plan").put("reply", missing);
            FakeLlmClient llm = new FakeLlmClient(new ChatResponse(envelope.toString(), List.of(), 10, 5));
            ListSink sink = new ListSink();
            List<String> deltas = new ArrayList<>();
            ExecutionResult result = runtime(llm, List.of(ticker), new DagConfig())
                    .execute(question, "", "missing-result", sink, deltas::add);
            assertEquals(missing, result.answer());
            assertEquals(0, ticker.calls);
            assertEquals(0, result.toolCallCount());
            assertEquals(0, result.evidence().size());
            assertEquals(1, llm.seenMessages.size());
            assertEquals(List.of(missing), deltas);
            assertTrue(sink.events.stream().noneMatch(e -> "answer".equals(e.name())));
            // 不再用分类围栏的话术覆盖 planner 的具体能力缺口说明。
            assertTrue(sink.events.stream().noneMatch(e -> e.error() != null));
        }
    }

    @Test
    void planWithoutQueryRequirementsExecutesNormally() {
        EchoTool ticker = new EchoTool("get_ticker");
        FakeLlmClient llm = new FakeLlmClient(new ChatResponse("""
                {"in_domain":true,"intent":"MARKET_LOOKUP","plan":{"nodes":[
                  {"id":"n1","tool":"get_ticker","args":{"symbol":"BTC"}}
                ]}}
                """, List.of(), 10, 5), new ChatResponse("当前价格", List.of(), 10, 5));
        ExecutionResult result = runtime(llm, List.of(ticker), new DagConfig())
                .execute("BTC 当前价格", "", "without-requirements", new ListSink());
        assertEquals("当前价格", result.answer());
        assertEquals(1, ticker.calls);
        assertEquals(2, llm.seenMessages.size());
    }

    @Test
    void partialQueryCarriesMissingCalculationAndAnswerConstraints() {
        ObjectNode data = MAPPER.createObjectNode();
        data.putObject("statistics").put("quote_volume", 12345);
        EchoTool statistics = new EchoTool("get_market_statistics", data);
        FakeLlmClient llm = new FakeLlmClient(new ChatResponse("""
                {"in_domain":true,"intent":"MARKET_LOOKUP","reply":"可以查询昨天成交额，但暂时无法计算同比。",
                 "plan":{"nodes":[{"id":"n1","tool":"get_market_statistics","args":{"symbol":"BTC"}}]}}
                """, List.of(), 10, 5), new ChatResponse("昨天成交额为 12345 USDT，暂时无法给出同比。", List.of(), 10, 5));
        ExecutionResult result = runtime(llm, List.of(statistics), new DagConfig())
                .execute("币安昨天成交额多少，另外给我同比", "", "partial", new ListSink());
        assertEquals(1, statistics.calls);
        assertEquals(2, llm.seenMessages.size());
        String system = llm.seenMessages.get(1).get(0).content();
        String user = llm.seenMessages.get(1).get(1).content();
        assertTrue(system.contains("不得自行补算或推导"));
        assertTrue(system.contains("工具调用成功不代表整个任务已经完成"));
        assertTrue(system.contains("工具已计算的涨跌幅、基差、占比等可以直接引用"));
        assertTrue(user.contains("<QUERY_STATUS>"));
        assertTrue(user.contains("暂时无法计算同比"));
        assertEquals(12345, result.evidence().get(0).path("data").path("statistics").path("quote_volume").asInt());
    }

    @Test
    void supportedTickerWindowStillExecutesAndReachesAnswer() {
        EchoTool ticker = new EchoTool("get_ticker");
        ObjectNode envelope = MAPPER.createObjectNode().put("in_domain", true).put("intent", "MARKET_LOOKUP");
        envelope.putObject("plan").putArray("nodes").addObject()
                .put("id", "n1").put("tool", "get_ticker").putObject("args").put("symbol", "BTC");
        FakeLlmClient llm = new FakeLlmClient(new ChatResponse(envelope.toString(), List.of(), 10, 5),
                new ChatResponse("滚动窗口查询结果", List.of(), 20, 8));
        ExecutionResult result = runtime(llm, List.of(ticker), new DagConfig())
                .execute("币安 BTC 过去24小时行情", "", "supported", new ListSink());
        assertEquals(1, ticker.calls);
        assertEquals(2, llm.seenMessages.size());
        assertEquals("滚动窗口查询结果", result.answer());
    }

}
