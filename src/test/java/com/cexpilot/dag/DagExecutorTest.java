package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DagExecutor：层内并行、依赖顺序、上游失败下游跳过、节点超时记 failure 不中断整体。
 */
class DagExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    abstract static class BaseTool implements com.cexpilot.runtime.TestTools.TestTool {
        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public JsonNode inputSchema() {
            return MAPPER.createObjectNode();
        }
    }

    static class ListSink implements TraceSink {
        final List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void record(TraceEvent event) {
            events.add(event);
        }
    }

    /** 两个并发节点互相等待对方到场才放行；串行执行时双方都会超时失败。 */
    static class LatchTool extends BaseTool {
        private final String name;
        private final CountDownLatch rendezvous;

        LatchTool(String name, CountDownLatch rendezvous) {
            this.name = name;
            this.rendezvous = rendezvous;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            rendezvous.countDown();
            try {
                if (!rendezvous.await(2, TimeUnit.SECONDS)) {
                    return ToolResult.failure("未等到并行节点（疑似串行执行）");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("interrupted");
            }
            return ToolResult.success(MAPPER.createObjectNode().put("from", name));
        }
    }

    static class RecordingTool extends BaseTool {
        private final String name;
        private final List<String> order;
        JsonNode seenArgs;

        RecordingTool(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            seenArgs = args;
            order.add(name);
            return ToolResult.success(MAPPER.createObjectNode().put("symbol", name + "-sym"));
        }
    }

    static class FailingTool extends BaseTool {
        @Override
        public String name() {
            return "failing_tool";
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            throw new RuntimeException("boom");
        }
    }

    static class SlowTool extends BaseTool {
        @Override
        public String name() {
            return "slow_tool";
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success(MAPPER.createObjectNode());
        }
    }

    private static DagConfig config() {
        return new DagConfig();
    }

    private static DagPlan plan(String json) {
        try {
            return DagPlan.fromJson(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void sameLayerRunsInParallel() {
        CountDownLatch rendezvous = new CountDownLatch(2);
        DagExecutor executor = new DagExecutor(com.cexpilot.runtime.TestTools.registry(List.of(
                new LatchTool("tool_x", rendezvous), new LatchTool("tool_y", rendezvous))), config());
        DagPlan plan = plan("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_x", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "tool_y", "args": {}, "depends_on": []}
                ]}
                """);

        DagExecutor.ExecutionOutcome outcome = executor.execute(plan, "trace-1", new ListSink());
        executor.shutdown();

        // 串行执行时 latch 等待会超时失败；成功即证明同层并行
        assertTrue(outcome.context().get("n1").ok());
        assertTrue(outcome.context().get("n2").ok());
        assertEquals(1, outcome.layers());
    }

    @Test
    void dependencyOrderAndReferenceResolution() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        RecordingTool upstream = new RecordingTool("upstream_tool", order);
        RecordingTool downstream = new RecordingTool("downstream_tool", order);
        ListSink sink = new ListSink();
        DagExecutor executor = new DagExecutor(com.cexpilot.runtime.TestTools.registry(List.of(upstream, downstream)), config());
        DagPlan plan = plan("""
                {"nodes": [
                  {"id": "n1", "tool": "upstream_tool", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "downstream_tool", "args": {"symbol": "{{n1.data.symbol}}"}, "depends_on": ["n1"]}
                ]}
                """);

        DagExecutor.ExecutionOutcome outcome = executor.execute(plan, "trace-2", sink);
        executor.shutdown();

        assertEquals(List.of("upstream_tool", "downstream_tool"), order);
        assertEquals(2, outcome.layers());
        // 下游拿到了引用解析后的值
        assertEquals("upstream_tool-sym", downstream.seenArgs.get("symbol").asText());
        // trace：两条 TOOL_CALL，inputJson 含 nodeId 与解析后 args
        assertEquals(2, sink.events.size());
        assertTrue(sink.events.get(1).inputJson().contains("\"node_id\":\"n2\""));
        assertTrue(sink.events.get(1).inputJson().contains("upstream_tool-sym"));
    }

    @Test
    void failedUpstreamSkipsDownstream() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        RecordingTool downstream = new RecordingTool("downstream_tool", order);
        ListSink sink = new ListSink();
        DagExecutor executor = new DagExecutor(
                com.cexpilot.runtime.TestTools.registry(List.of(new FailingTool(), downstream)), config());
        DagPlan plan = plan("""
                {"nodes": [
                  {"id": "n1", "tool": "failing_tool", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "downstream_tool", "args": {}, "depends_on": ["n1"]}
                ]}
                """);

        DagExecutor.ExecutionOutcome outcome = executor.execute(plan, "trace-3", sink);
        executor.shutdown();

        assertFalse(outcome.context().get("n1").ok());
        ToolResult skipped = outcome.context().get("n2");
        assertFalse(skipped.ok());
        assertTrue(skipped.error().contains("skipped: 上游节点失败"));
        // 下游工具没有真正执行
        assertTrue(order.isEmpty());
    }

    @Test
    void nodeTimeoutMarkedFailureWithoutBreakingOthers() {
        DagConfig config = config();
        config.setNodeTimeoutMs(200);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        RecordingTool fast = new RecordingTool("fast_tool", order);
        ListSink sink = new ListSink();
        DagExecutor executor = new DagExecutor(
                com.cexpilot.runtime.TestTools.registry(List.of(new SlowTool(), fast)), config);
        DagPlan plan = plan("""
                {"nodes": [
                  {"id": "n1", "tool": "slow_tool", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "fast_tool", "args": {}, "depends_on": []}
                ]}
                """);

        DagExecutor.ExecutionOutcome outcome = executor.execute(plan, "trace-4", sink);
        executor.shutdown();

        ToolResult slow = outcome.context().get("n1");
        assertFalse(slow.ok());
        assertTrue(slow.error().contains("超时"));
        // 同层另一个节点不受影响
        assertTrue(outcome.context().get("n2").ok());
        assertEquals(List.of("fast_tool"), order);
    }

    /**
     * 节点超时后工作线程可能仍存活（cancel 不保证终止）：迟到的子调用事件必须被丢弃，
     * 不能让主线程在 collect 里与 worker 并发读写同一列表（曾因此 CME 升级为整次执行失败）。
     */
    @Test
    void timeoutDropsLateSubCallEvents() throws Exception {
        DagConfig config = config();
        config.setNodeTimeoutMs(200);
        CountDownLatch lateRecorded = new CountDownLatch(1);
        AgentTool lateWriter = new BaseTool() {
            @Override
            public String name() {
                return "late_recording_tool";
            }

            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                // 不响应 interrupt（模拟卡死的 HTTP 调用），且必须睡到主线程冻结之后，
                // 否则被 cancel 唤醒后的写入会与冻结竞争，时序不确定
                long deadline = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(deadline - System.currentTimeMillis());
                    } catch (InterruptedException ignored) {
                    }
                }
                // 主线程早已超时回收：这是一次"迟到"的子调用事件写入
                com.cexpilot.runtime.NodeTraceCollector.record("mcp.fake", "{}", "{}", 1, null);
                lateRecorded.countDown();
                return ToolResult.success(MAPPER.createObjectNode());
            }
        };
        ListSink sink = new ListSink();
        DagExecutor executor = new DagExecutor(
                com.cexpilot.runtime.TestTools.registry(List.of(lateWriter)), config);
        DagPlan plan = plan("""
                {"nodes": [{"id": "n1", "tool": "late_recording_tool", "args": {}, "depends_on": []}]}
                """);

        DagExecutor.ExecutionOutcome outcome = executor.execute(plan, "trace-late", sink);

        assertFalse(outcome.context().get("n1").ok());
        assertTrue(outcome.context().get("n1").error().contains("超时"));
        // 迟到写入确实发生了（否则测试没覆盖到目标时序）
        assertTrue(lateRecorded.await(3, TimeUnit.SECONDS));
        // 但它被冻结丢弃，没有进入 trace，执行也没有因并发异常而失败
        assertTrue(sink.events.stream().noneMatch(e -> "mcp.fake".equals(e.name())));
        executor.shutdown();
    }
    @Test
    void yamlDefaultsAndResolvedArgumentValidationReachExecutor() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AgentTool upstream = new AgentTool() {
            public String name() { return "upstream"; }
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                return ToolResult.success(MAPPER.createObjectNode().put("limit", 100));
            }
        };
        AgentTool downstream = new AgentTool() {
            public String name() { return "downstream"; }
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                calls.incrementAndGet();
                return ToolResult.success(args);
            }
        };
        var empty = MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}");
        var bounded = MAPPER.readTree("""
                {"type":"object","properties":{"limit":{"type":"integer","default":3,"maximum":5}}}
                """);
        ToolRegistry registry = new ToolRegistry(List.of(upstream, downstream), List.of(
                new com.cexpilot.runtime.ToolDefinition("upstream", true, "上游", empty, MAPPER.createObjectNode()),
                new com.cexpilot.runtime.ToolDefinition("downstream", true, "下游", bounded, MAPPER.createObjectNode())));
        DagPlan plan = DagPlan.fromJson(MAPPER.readTree("""
                {"nodes":[
                  {"id":"n1","tool":"upstream","args":{}},
                  {"id":"n2","tool":"downstream","args":{}},
                  {"id":"n3","tool":"downstream","args":{"limit":"{{n1.data.limit}}"},"depends_on":["n1"]}
                ]}
                """));
        assertTrue(new PlanValidator(registry, config()).validate(plan, null, 8).isEmpty());
        DagExecutor executor = new DagExecutor(registry, config());
        try {
            var execution = executor.execute(plan, "defaults", new ListSink());
            assertEquals(3, execution.context().get("n2").data().path("limit").asInt());
            assertFalse(execution.context().get("n3").ok());
            assertTrue(execution.context().get("n3").error().contains("maximum"));
            assertEquals(1, calls.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void reuseFactSeedsContextWithoutProviderAndFeedsCalculation() throws Exception {
        // F05 形态：m1 币安（REUSE 旧快照 83000）+ m2 OKX（FETCH 90000）→ relative_change
        java.util.function.BiFunction<String, String, com.fasterxml.jackson.databind.node.ObjectNode> group =
                (id, exchange) -> {
                    var g = MAPPER.createObjectNode().put("id", id).put("metric", "price.last")
                            .put("query_shape", "snapshot");
                    g.putArray("exchanges").add(exchange);
                    g.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
                    return g;
                };
        ObjectNode planJson = MAPPER.createObjectNode();
        var metrics = planJson.putArray("metrics");
        metrics.add(group.apply("m1", "binance"));
        metrics.add(group.apply("m2", "okx"));
        planJson.putArray("calculations").addObject().put("id", "c1").put("operator", "relative_change")
                .set("input", MAPPER.readTree("{\"current\":\"{{m2.okx.value}}\",\"baseline\":\"{{m1.binance.value}}\"}"));
        var compiler = new com.cexpilot.metric.MetricPlanCompiler(
                new com.cexpilot.metric.MetricCatalog(new org.springframework.core.io.DefaultResourceLoader()),
                com.cexpilot.dag.MetricTestSupport.registry(), com.cexpilot.dag.MetricTestSupport.catalogProviders());
        var compiled = compiler.compileFull(planJson, 8);

        // 币安节点绑定历史快照（payload 为缓存的 MetricResultJson 输出）
        var target = compiled.targets().stream().filter(t -> t.physicalId().equals("metric_0")).findFirst().orElseThrow();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("group_id", "m1").put("metric", "price.last").put("exchange", "binance")
                .put("query_shape", "snapshot").put("unit", "USDT")
                .put("catalog_version", target.catalogVersion())
                .put("as_of", "2026-10-12 09:00:00").put("value", 83000)
                .put("estimated", false).put("truncated", false);
        payload.set("instrument", target.instrument());
        payload.putObject("source").put("provider", target.provider()).put("selector", "LAST");
        var bindings = new com.cexpilot.metric.ExecutionBindings(java.util.Map.of("metric_0",
                new com.cexpilot.metric.ExecutionBindings.ReuseFact("fact-1", "turn-1",
                        "2026-10-12 09:00:00", "2026-10-12T01:00:00Z", payload)));

        // Provider 桩：币安被复用不得调用；OKX 返回 90000
        var ticker = MetricTestSupport.namedProvider(
                com.cexpilot.metric.TickerMetricProvider.NAME, com.cexpilot.metric.TickerMetric::valueOf, (q, c) -> {
                    if (((com.cexpilot.metric.SnapshotQuery) q).exchange() == com.cexpilot.market.Exchange.BINANCE) {
                        throw new AssertionError("REUSE_FACT 节点不得访问 Provider");
                    }
                    return new com.cexpilot.metric.MetricResult.Point(
                            new com.cexpilot.metric.MetricResult.SnapshotMetadata(
                                    java.time.Instant.parse("2026-10-12T02:00:00Z"), java.time.ZoneId.of("Asia/Shanghai")),
                            new java.math.BigDecimal("90000"));
                });
        ListSink sink = new ListSink();
        DagExecutor executor = new DagExecutor(com.cexpilot.dag.MetricTestSupport.registry(), config(),
                new com.cexpilot.metric.MetricProviderRegistry(List.of(ticker)));
        try {
            var outcome = executor.execute(compiled.dagPlan(), "trace-reuse", sink, null, bindings);

            assertTrue(outcome.context().get("metric_0").ok());
            assertEquals(83000, outcome.context().get("metric_0").data().path("value").asDouble());
            assertTrue(outcome.context().get("metric_1").ok());
            ToolResult calc = outcome.context().get("c1");
            assertTrue(calc.ok(), String.valueOf(calc.error()));
            // current=90000(OKX 新取) baseline=83000(币安复用) → 正百分比，方向不得交换
            assertEquals(8.4337, calc.data().path("percent").asDouble(), 0.001);
            // 复用不落 METRIC_RESULT（保持"本轮真实取数"语义），落 FACT_REUSED
            assertEquals(1, sink.events.stream().filter(e -> e.eventType().equals("FACT_REUSED")).count());
            assertEquals(1, sink.events.stream().filter(e -> e.eventType().equals("METRIC_RESULT")).count());
            var reused = sink.events.stream().filter(e -> e.eventType().equals("FACT_REUSED")).findFirst().orElseThrow();
            assertTrue(reused.inputJson().contains("\"fact_id\":\"fact-1\""));
            assertTrue(reused.inputJson().contains("\"source_turn_id\":\"turn-1\""));
            assertTrue(reused.inputJson().contains("PINNED_RESULT"));
        } finally {
            executor.shutdown();
        }
    }
}
