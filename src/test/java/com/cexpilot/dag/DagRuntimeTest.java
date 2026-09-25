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
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        ToolRegistry registry = com.cexpilot.runtime.TestTools.registry(tools);
        PromptStore prompts = new PromptStore(new DefaultResourceLoader());
        IntentRegistry intentRegistry = new IntentRegistry(new DefaultResourceLoader());
        DagPlanner planner = new DagPlanner(llm, registry, intentRegistry, new LlmConfig(), dagConfig,
                prompts, new PlanValidator(registry, dagConfig),
                com.cexpilot.dag.guard.QueryCapabilityGuard.defaults());
        return new DagRuntime(llm, planner, new DagExecutor(registry, dagConfig), prompts, intentRegistry);
    }

    @Test
    void endToEndPlanExecuteAnswer() {
        EchoTool toolA = new EchoTool("tool_a");
        EchoTool toolB = new EchoTool("tool_b");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true, "query_requirements":{"requires_period_comparison":false}, "intent": "MARKET_LOOKUP", "reply": null,
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

        // 最终回答调用的 user 消息带上了 evidence 摘要
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
                        {"in_domain": true, "query_requirements":{"requires_period_comparison":false}, "intent": "MARKET_LOOKUP", "reply": null,
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
    void answerReceivesSummarizedFactsWithoutDetailArrays() {
        ObjectNode klineData = MAPPER.createObjectNode();
        klineData.put("symbol", "BTC");
        klineData.putObject("funding_summary").put("change_pct", 2.1);
        ArrayNode recent_rates = klineData.putArray("rates");
        for (int i = 0; i < 100; i++) {
            recent_rates.addArray().add(i);
        }
        EchoTool klines = new EchoTool("get_funding_rate_history", klineData);
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true, "query_requirements":{"requires_period_comparison":false}, "intent": "MARKET_ANALYSIS", "reply": null,
                         "plan": {"nodes": [
                           {"id": "n1", "tool": "get_funding_rate_history", "args": {}, "depends_on": []}
                         ]}}
                        """, List.of(), 10, 5),
                new ChatResponse("概览回答", List.of(), 20, 8));
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(klines), new DagConfig())
                .execute("行情如何？", "", "trace-5", sink);

        // 完整 evidence（含明细）仍随结果返回
        assertTrue(result.evidence().toString().contains("rates"));
        // answer 的 user 消息只有摘要：保留已计算指标，省略明细数组并标注
        ChatMessage user = llm.seenMessages.get(1).get(1);
        assertTrue(user.content().contains("funding_summary"));
        assertTrue(user.content().contains("rates(100条)"));
        assertFalse(user.content().contains("[0],[1]"));
        // 分析类也受事实约束，但允许用户请求的证据分析
        assertTrue(llm.seenMessages.get(1).get(0).content().contains("用户要求分析时"));
    }

    @Test
    void exchangeComparisonUsesSameFactBoundaries() {
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true, "query_requirements":{"requires_period_comparison":false}, "intent": "EXCHANGE_COMPARE", "reply": null,
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
                new ChatResponse("{\"in_domain\": true, \"query_requirements\":{\"requires_period_comparison\":false}, \"intent\": \"UNKNOWN\","
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
                new ChatResponse("{\"in_domain\": true, \"query_requirements\":{\"requires_period_comparison\":false}, \"intent\": \"MARKET_LOOKUP\","
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
                        {"in_domain":true,"query_requirements":{"requires_period_comparison":false},"intent":"MARKET_LOOKUP","reply":"仅有最近两期数据",
                         "plan":{"nodes":[{"id":"n1","tool":"get_funding_rate_history","args":{},
                         "depends_on":[],"include_details":true}]}}
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
                        {"in_domain": true, "query_requirements":{"requires_period_comparison":false}, "intent": "MARKET_LOOKUP", "reply": null,
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
                        {"in_domain": true, "query_requirements":{"requires_period_comparison":false}, "intent": "MARKET_LOOKUP", "reply": null,
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
    void periodComparisonRefusesBeforeToolsAndAnswerIncludingStreaming() {
        for (String question : List.of("拿币安 BTC-USDC 上周总交易额跟上上周对比",
                "币安 BTC 成交量同比", "BTC 本周对上周涨跌")) {
            EchoTool ticker = new EchoTool("get_ticker");
            ObjectNode envelope = MAPPER.createObjectNode().put("in_domain", true).put("intent", "MARKET_LOOKUP");
            // 即使给出取数计划，任务级比较能力未开放时仍整单拒答。
            envelope.set("query_requirements",
                    com.cexpilot.dag.guard.QueryCapabilityGuardTest.requirements(true));
            envelope.putObject("plan").putArray("nodes").addObject()
                    .put("id", "n1").put("tool", "get_ticker").putObject("args").put("symbol", "BTC");
            FakeLlmClient llm = new FakeLlmClient(new ChatResponse(envelope.toString(), List.of(), 10, 5));
            ListSink sink = new ListSink();
            List<String> deltas = new ArrayList<>();
            ExecutionResult result = runtime(llm, List.of(ticker), new DagConfig())
                    .execute(question, "", "refused", sink, deltas::add);
            assertTrue(result.answer().contains("无法"), result.answer());
            assertEquals(0, ticker.calls);
            assertEquals(0, result.toolCallCount());
            assertEquals(0, result.evidence().size());
            assertEquals(1, llm.seenMessages.size());
            assertEquals(List.of(result.answer()), deltas);
            assertTrue(sink.events.stream().anyMatch(e -> e.error() != null && e.error().startsWith("CAPABILITY_REFUSED")));
            assertTrue(sink.events.stream().noneMatch(e -> "answer".equals(e.name())));
        }
    }

    @Test
    void missingRequirementsCannotExecuteLegacyPlan() {
        EchoTool ticker = new EchoTool("get_ticker");
        FakeLlmClient llm = new FakeLlmClient(new ChatResponse("""
                {"in_domain":true,"intent":"MARKET_LOOKUP","plan":{"nodes":[
                  {"id":"n1","tool":"get_ticker","args":{"symbol":"BTC"}}
                ]}}
                """, List.of(), 10, 5));
        ExecutionResult result = runtime(llm, List.of(ticker), new DagConfig())
                .execute("BTC 当前价格", "", "legacy", new ListSink());
        assertEquals(com.cexpilot.dag.guard.GuardMessages.UNCONFIRMED, result.answer());
        assertEquals(0, ticker.calls);
        assertEquals(1, llm.seenMessages.size());
    }

    @Test
    void supportedTickerWindowStillExecutesAndReachesAnswer() {
        EchoTool ticker = new EchoTool("get_ticker");
        ObjectNode envelope = MAPPER.createObjectNode().put("in_domain", true).put("intent", "MARKET_LOOKUP");
        envelope.set("query_requirements",
                com.cexpilot.dag.guard.QueryCapabilityGuardTest.requirements(false));
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
