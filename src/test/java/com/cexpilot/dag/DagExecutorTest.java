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

    abstract static class BaseTool implements AgentTool {
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
        DagExecutor executor = new DagExecutor(new ToolRegistry(List.of(
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
        DagExecutor executor = new DagExecutor(new ToolRegistry(List.of(upstream, downstream)), config());
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
                new ToolRegistry(List.of(new FailingTool(), downstream)), config());
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
                new ToolRegistry(List.of(new SlowTool(), fast)), config);
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
}
