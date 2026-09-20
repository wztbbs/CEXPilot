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
                prompts, new PlanValidator(registry, dagConfig));
        return new DagRuntime(llm, planner, new DagExecutor(registry, dagConfig), prompts, intentRegistry);
    }

    @Test
    void endToEndPlanExecuteAnswer() {
        EchoTool toolA = new EchoTool("tool_a");
        EchoTool toolB = new EchoTool("tool_b");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true, "intent": "MARKET_LOOKUP", "reply": null,
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
    void answerReceivesSummarizedFactsWithoutDetailArrays() {
        ObjectNode klineData = MAPPER.createObjectNode();
        klineData.put("symbol", "BTC");
        klineData.putObject("price_change").put("change_pct", 2.1);
        ArrayNode candles = klineData.putArray("candles");
        for (int i = 0; i < 100; i++) {
            candles.addArray().add(i);
        }
        EchoTool klines = new EchoTool("get_klines", klineData);
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true, "intent": "MARKET_ANALYSIS", "reply": null,
                         "plan": {"nodes": [
                           {"id": "n1", "tool": "get_klines", "args": {}, "depends_on": []}
                         ]}}
                        """, List.of(), 10, 5),
                new ChatResponse("概览回答", List.of(), 20, 8));
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(klines), new DagConfig())
                .execute("行情如何？", "", "trace-5", sink);

        // 完整 evidence（含明细）仍随结果返回
        assertTrue(result.evidence().toString().contains("candles"));
        // answer 的 user 消息只有摘要：保留已计算指标，省略明细数组并标注
        ChatMessage user = llm.seenMessages.get(1).get(1);
        assertTrue(user.content().contains("price_change"));
        assertTrue(user.content().contains("candles(100条)"));
        assertFalse(user.content().contains("[0],[1]"));
        // 分析类也受事实约束，但允许用户请求的证据分析
        assertTrue(llm.seenMessages.get(1).get(0).content().contains("用户要求分析时"));
    }

    @Test
    void exchangeComparisonUsesSameFactBoundaries() {
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain": true, "intent": "EXCHANGE_COMPARE", "reply": null,
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
    void nullPlanPassesEmptyFactsAndMissingReason() {
        EchoTool tool = new EchoTool("tool_a");
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("{\"in_domain\": true, \"intent\": \"UNKNOWN\","
                        + " \"reply\": \"缺少链上持仓数据\", \"plan\": null}", List.of(), 10, 5),
                new ChatResponse("缺少查询事实，无法确认", List.of(), 20, 8));
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(tool), new DagConfig())
                .execute("这个问题工具不够", "", "trace-3", sink);

        assertEquals("缺少查询事实，无法确认", result.answer());
        assertEquals("UNKNOWN", result.intent());
        assertEquals(0, tool.calls);
        assertEquals(0, result.toolCallCount());
        assertEquals(0, result.evidence().size());
        assertEquals(30, result.promptTokens());
        // 降级回答了，user 消息注明局限
        List<ChatMessage> answerCall = llm.seenMessages.get(1);
        ChatMessage user = answerCall.get(answerCall.size() - 1);
        assertTrue(user.content().contains("<FACTS>\n[]\n</FACTS>"));
        assertTrue(user.content().contains("缺少链上持仓数据"));
        assertFalse(user.content().contains("请基于已有知识"));
        assertTrue(answerCall.get(0).content().contains("不得凭已有知识补全缺失事实"));
        // 降级原因落 PLAN trace
        assertTrue(sink.events.stream().anyMatch(e -> e.eventType().equals("PLAN")
                && e.error() != null && e.error().contains("没有可执行的查询计划")));
    }

    @Test
    void plannerFailurePassesEmptyFactsWithoutKnowledgeFallback() {
        EchoTool tool = new EchoTool("tool_a");
        // planner 两轮都返回非 JSON，重试耗尽；第 3 次调用是降级回答
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("garbage", List.of(), 10, 5),
                new ChatResponse("still garbage", List.of(), 10, 5),
                new ChatResponse("缺少查询事实，无法确认", List.of(), 20, 8));
        DagConfig dagConfig = new DagConfig();
        dagConfig.setPlannerMaxRetries(1);
        ListSink sink = new ListSink();

        ExecutionResult result = runtime(llm, List.of(tool), dagConfig)
                .execute("BTC 怎么了？", "", "trace-4", sink);

        assertEquals("缺少查询事实，无法确认", result.answer());
        assertEquals("UNKNOWN", result.intent());
        assertEquals(0, tool.calls);
        assertEquals(0, result.toolCallCount());
        assertEquals(0, result.steps());
        assertEquals(0, result.evidence().size());
        assertEquals(40, result.promptTokens());

        // trace：2 次 planner LLM_CALL + 2 条解析失败 PLAN + 1 条降级 PLAN + 1 次 answer
        long planErrors = sink.events.stream()
                .filter(e -> e.eventType().equals("PLAN") && e.error() != null).count();
        assertEquals(3, planErrors);
        assertTrue(sink.events.stream().noneMatch(e -> e.eventType().equals("TOOL_CALL")));
    }
    @Test
    void requestedHistoryAndPartialQueryGapReachAnswer() {
        ObjectNode data = MAPPER.createObjectNode();
        data.putArray("recent_rates").add(0.0001).add(0.0002);
        EchoTool tool = new EchoTool("get_funding_rate", data);
        FakeLlmClient llm = new FakeLlmClient(
                new ChatResponse("""
                        {"in_domain":true,"intent":"MARKET_LOOKUP","reply":"仅有最近两期数据",
                         "plan":{"nodes":[{"id":"n1","tool":"get_funding_rate","args":{},
                         "depends_on":[],"include_details":true}]}}
                        """, List.of(), 10, 5),
                new ChatResponse("最近两期费率", List.of(), 20, 8));
        runtime(llm, List.of(tool), new DagConfig()).execute("列出最近10期费率", "", "details", new ListSink());
        String answerInput = llm.seenMessages.get(1).get(1).content();
        assertTrue(answerInput.contains("\"recent_rates\":[1.0E-4,2.0E-4]")
                || answerInput.contains("\"recent_rates\":[0.0001,0.0002]"));
        assertTrue(answerInput.contains("仅有最近两期数据"));
        assertFalse(answerInput.contains("明细序列已省略"));
    }
}
