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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 有界 DAG 执行引擎：Kahn 拓扑分层，同层节点（依赖已全部满足）提交固定线程池并行执行，
 * 层间屏障等待全部完成后再调度下一层。
 *
 * 并发纪律（沿用 merchant 项目的约定）：工作线程只读 DagContext，只做依赖检查、
 * 引用解析、tool.execute；结果的写入 DagContext 与 TOOL_CALL trace 统一由主线程
 * 在层间完成，因此层内不存在共享态写竞争。
 *
 * 失败语义：任一节点失败不中断整体——直接依赖失败的节点记 "skipped: 上游节点失败"；
 * 节点超时 future.cancel(true) 记 failure（HTTP 客户端不保证响应中断，仅作预算兜底）。
 */
@Component
public class DagExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry registry;
    private final DagConfig config;
    private final ExecutorService pool;

    public DagExecutor(ToolRegistry registry, DagConfig config) {
        this.registry = registry;
        this.config = config;
        this.pool = Executors.newFixedThreadPool(config.getExecutorThreads());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /** 执行结果：节点产出上下文 + 拓扑层数（作为 ExecutionResult.steps）。 */
    public record ExecutionOutcome(DagContext context, int layers) {
    }

    /** 单节点的工作线程产出：结果 + 解析后的入参（供主线程落 trace）。 */
    private record NodeOutcome(ToolResult result, String resolvedArgsJson, long durationMs) {
    }

    public ExecutionOutcome execute(DagPlan plan, String traceId, TraceSink sink) {
        DagContext ctx = new DagContext();
        List<List<PlanNode>> levels = topoLevels(plan);
        for (List<PlanNode> level : levels) {
            List<Future<NodeOutcome>> futures = new ArrayList<>();
            for (PlanNode node : level) {
                futures.add(pool.submit(() -> runNode(node, ctx, traceId)));
            }
            for (int i = 0; i < level.size(); i++) {
                collect(level.get(i), futures.get(i), ctx, traceId, sink);
            }
        }
        return new ExecutionOutcome(ctx, levels.size());
    }

    /** 工作线程：依赖检查 → 引用解析 → 工具执行；一切异常转为 ToolResult.failure。 */
    private NodeOutcome runNode(PlanNode node, DagContext ctx, String traceId) {
        long start = System.currentTimeMillis();
        for (String dep : node.dependsOn()) {
            ToolResult depResult = ctx.get(dep);
            if (depResult == null || !depResult.ok()) {
                log.warn("节点 {} 工具 {} 跳过: 上游节点 {} 失败", node.id(), node.tool(), dep);
                return new NodeOutcome(ToolResult.failure("skipped: 上游节点失败"),
                        null, System.currentTimeMillis() - start);
            }
        }
        JsonNode resolvedArgs = null;
        ToolResult result;
        try {
            resolvedArgs = registry.prepareArguments(node.tool(), ReferenceResolver.resolve(node.args(), ctx));
            AgentTool tool = registry.get(node.tool());
            result = tool.execute(resolvedArgs, new ToolContext(traceId, null));
        } catch (Exception e) {
            log.warn("节点 {} 工具 {} 执行异常: {}", node.id(), node.tool(), e.getMessage());
            return new NodeOutcome(ToolResult.failure("工具执行异常: " + e.getMessage()),
                    String.valueOf(resolvedArgs == null ? node.args() : resolvedArgs),
                    System.currentTimeMillis() - start);
        }
        if (!result.ok()) {
            // 工具内部已把异常转成 failure（如交易所 451），异常路径的 warn 不会触发，这里必须补一条
            log.warn("节点 {} 工具 {} 执行失败: {}", node.id(), node.tool(), result.error());
        }
        return new NodeOutcome(result,
                resolvedArgs == null ? null : resolvedArgs.toString(),
                System.currentTimeMillis() - start);
    }

    /** 主线程回收：带超时等待 → 写 DagContext → 落 TOOL_CALL trace（inputJson 含 nodeId 与解析后 args）。 */
    private void collect(PlanNode node, Future<NodeOutcome> future, DagContext ctx,
                         String traceId, TraceSink sink) {
        NodeOutcome outcome;
        try {
            outcome = future.get(config.getNodeTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("节点 {} 执行超时（{}ms），按失败处理", node.id(), config.getNodeTimeoutMs());
            outcome = new NodeOutcome(
                    ToolResult.failure("节点执行超时（" + config.getNodeTimeoutMs() + "ms）"),
                    node.args() == null ? null : node.args().toString(), config.getNodeTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = new NodeOutcome(ToolResult.failure("等待节点执行时被中断: " + e.getMessage()),
                    null, 0);
        } catch (ExecutionException e) {
            // 工作线程已吞掉所有异常，正常不会到这里；兜底防御
            outcome = new NodeOutcome(ToolResult.failure("节点执行异常: " + e.getCause()),
                    null, 0);
        }
        ToolResult result = outcome.result();
        ctx.put(node.id(), result);
        sink.record(TraceEvent.toolCall(traceId, node.tool(),
                eventInput(node.id(), outcome.resolvedArgsJson()),
                result.toMessageContent(), outcome.durationMs(),
                result.ok() ? null : result.error()));
    }

    private static String eventInput(String nodeId, String resolvedArgsJson) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("node_id", nodeId);
        try {
            node.set("args", resolvedArgsJson == null
                    ? MAPPER.createObjectNode() : MAPPER.readTree(resolvedArgsJson));
        } catch (Exception e) {
            node.put("args", resolvedArgsJson);
        }
        return node.toString();
    }

    /** Kahn 拓扑分层：每轮 indegree=0 的节点为一层，同层互不依赖可并行（plan 已通过校验无环）。 */
    private List<List<PlanNode>> topoLevels(DagPlan plan) {
        Map<String, Integer> indegree = new HashMap<>();
        for (PlanNode node : plan.nodes()) {
            indegree.put(node.id(), node.dependsOn().size());
        }
        List<List<PlanNode>> levels = new ArrayList<>();
        List<PlanNode> remaining = new ArrayList<>(plan.nodes());
        while (!remaining.isEmpty()) {
            List<PlanNode> ready = remaining.stream()
                    .filter(n -> indegree.get(n.id()) == 0)
                    .toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("plan 依赖图存在环（应已在 PlanValidator 拦截）");
            }
            levels.add(ready);
            for (PlanNode node : ready) {
                remaining.remove(node);
                remaining.forEach(r -> {
                    if (r.dependsOn().contains(node.id())) {
                        indegree.merge(r.id(), -1, Integer::sum);
                    }
                });
            }
        }
        return levels;
    }
}
