package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.metric.*;
import com.cexpilot.calculation.CalculationTool;
import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.NodeTraceCollector;
import com.cexpilot.runtime.RequestContext;
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

import java.util.*;
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
 * 指标 Provider 查询或计算工具执行；结果的写入 DagContext 与 trace 统一由主线程
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
    private final MetricProviderRegistry metricProviders;
    private final ExecutorService pool;

    public DagExecutor(ToolRegistry registry, DagConfig config) {
        this(registry, config, new MetricProviderRegistry(List.of()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DagExecutor(ToolRegistry registry, DagConfig config, MetricProviderRegistry metricProviders) {
        this.metricProviders = metricProviders;
        this.registry = registry;
        this.config = config;
        this.pool = Executors.newFixedThreadPool(config.getExecutorThreads());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
    /** 独立后台执行器的生命周期入口，不共享问答执行池。 */
    public void close() { shutdown(); }

    /** 执行结果：节点产出上下文 + 拓扑层数（作为 ExecutionResult.steps）+ 本轮真实 HTTP 请求数。 */
    public record ExecutionOutcome(DagContext context, int layers, int httpRequests) {
    }

    /** 单节点的工作线程产出：结果 + 解析后的入参（供主线程落 trace）。 */
    private record NodeOutcome(ToolResult result, String resolvedArgsJson, long durationMs) {
    }

    public ExecutionOutcome execute(DagPlan plan, String traceId, TraceSink sink) {
        return execute(plan, traceId, sink, null);
    }

    /**
     * @param requestContext 请求时间上下文（用户时区 + 固定 requestTime），随 ToolContext
     *                       传给每个工具节点；null 表示未携带
     */
    public ExecutionOutcome execute(DagPlan plan, String traceId, TraceSink sink,
                                    RequestContext requestContext) {
        return execute(plan, traceId, sink, requestContext, null);
    }

    /**
     * @param bindings 服务端生成的执行期绑定（阶段二）；非 null 时 REUSE_FACT 节点不走 Provider，
     *                 直接把历史事实 payload 播种进 DagContext（nodeId=本轮物理节点 id），
     *                 下游 ReferenceResolver/算子/来源校验零改动
     */
    public ExecutionOutcome execute(DagPlan plan, String traceId, TraceSink sink,
                                    RequestContext requestContext,
                                    com.cexpilot.metric.ExecutionBindings bindings) {
        return executeInternal(plan,traceId,sink,requestContext,bindings,Map.of(),(node,result)->{});
    }

    /** 监控执行恢复：种子必须由调用方校验来源、新鲜度与依赖；每个完成节点可持久化检查点。 */
    public ExecutionOutcome executeResumable(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            Map<String,ToolResult> seeds,java.util.function.BiConsumer<PlanNode,ToolResult> completed) {
        return executeInternal(plan,traceId,sink,requestContext,null,seeds,completed);
    }
    private ExecutionOutcome executeInternal(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            com.cexpilot.metric.ExecutionBindings bindings,Map<String,ToolResult> seeds,
            java.util.function.BiConsumer<PlanNode,ToolResult> completed) {
        DagContext ctx = new DagContext();
        seeds.forEach(ctx::put);
        // 本轮共享的 HTTP 请求计数器：工作线程进入节点执行前绑定到线程，
        // BinanceClient/OkxClient 每次真实发请求递增（一个节点分页多次请求会记多次）
        java.util.concurrent.atomic.AtomicInteger httpRequests = new java.util.concurrent.atomic.AtomicInteger();
        List<List<PlanNode>> levels = topoLevels(plan);
        for (List<PlanNode> fullLevel : levels) {
            List<PlanNode> level=fullLevel.stream().filter(node->ctx.get(node.id())==null).toList();
            List<Future<NodeOutcome>> futures = new ArrayList<>();
            List<NodeTraceCollector.Session> sessions = new ArrayList<>();
            for (PlanNode node : level) {
                NodeTraceCollector.Session session = new NodeTraceCollector.Session();
                sessions.add(session);
                futures.add(pool.submit(() -> runNode(node, ctx, traceId, requestContext, bindings, httpRequests, session)));
            }
            try {
                for (int i = 0; i < level.size(); i++) {
                    collect(level.get(i), futures.get(i), sessions.get(i), ctx, traceId, sink, bindings);
                    completed.accept(level.get(i),ctx.get(level.get(i).id()));
                }
            } finally {
                for(var future:futures) if(!future.isDone()) future.cancel(true);
                sessions.forEach(NodeTraceCollector.Session::freezeAndSnapshot);
            }
        }
        return new ExecutionOutcome(ctx, levels.size(), httpRequests.get());
    }

    /** 工作线程：依赖检查 → 引用解析 → 工具执行；一切异常转为 ToolResult.failure。 */
    private NodeOutcome runNode(PlanNode node, DagContext ctx, String traceId,
                                RequestContext requestContext,
                                com.cexpilot.metric.ExecutionBindings bindings,
                                java.util.concurrent.atomic.AtomicInteger httpRequests,
                                NodeTraceCollector.Session session) {
        com.cexpilot.market.HttpRequestCounter.bind(httpRequests);
        NodeTraceCollector.bind(session);
        try {
            return doRunNode(node, ctx, traceId, requestContext, bindings);
        } finally {
            com.cexpilot.market.HttpRequestCounter.clear();
            NodeTraceCollector.clear();
        }
    }

    private NodeOutcome doRunNode(PlanNode node, DagContext ctx, String traceId,
                                  RequestContext requestContext,
                                  com.cexpilot.metric.ExecutionBindings bindings) {
        long start = System.currentTimeMillis();
        for (String dep : node.dependsOn()) {
            ToolResult depResult = ctx.get(dep);
            if (depResult == null || !depResult.ok()) {
                log.warn("节点 {} 执行器 {} 跳过: 上游节点 {} 失败", node.id(), executionName(node), dep);
                return new NodeOutcome(ToolResult.failure("skipped: 上游节点失败"),
                        null, System.currentTimeMillis() - start);
            }
        }
        if (node.metric() != null && bindings != null && bindings.reuseFor(node.id()) != null) {
            // REUSE_FACT：不访问 Provider，直接以缓存 payload 作为本节点产出（保留原观测时间与口径）
            return new NodeOutcome(ToolResult.success(bindings.reuseFor(node.id()).payload().deepCopy()),
                    node.args().toString(), System.currentTimeMillis() - start);
        }
        JsonNode resolvedArgs = null;
        ToolResult result;
        try {
            if (node.metric() != null) {
                MetricResult metricResult = metricProviders.get(Objects.requireNonNull(node.metric()).provider()).query(node.metricQuery(), requestContext);
                return new NodeOutcome(ToolResult.success(MetricResultJson.write(node.metric(), metricResult)),
                        node.args().toString(), System.currentTimeMillis() - start);
            }
            AgentTool tool = registry.get(node.tool());
            if (tool instanceof CalculationTool calculation) {
                com.cexpilot.metric.MetricCalculationContext.validate(node, ctx);
                for (ReferenceResolver.Ref ref : ReferenceResolver.findRefs(node.args())) {
                    ToolResult source = ctx.get(ref.nodeId());
                    if (source != null && source.ok()) calculation.validateSource(source.data());
                }
            }
            resolvedArgs = registry.prepareArguments(node.tool(), ReferenceResolver.resolve(node.args(), ctx));
            result = tool.execute(resolvedArgs, new ToolContext(traceId, null,
                    requestContext == null ? null : requestContext.userZone(),
                    requestContext == null ? null : requestContext.requestTime()));
            if (tool instanceof CalculationTool) {
                result = com.cexpilot.metric.MetricCalculationContext.attach(node, ctx, result);
            }
        } catch (Exception e) {
            log.warn("节点 {} 执行器 {} 执行异常: {}", node.id(), executionName(node), e.getMessage());
            return new NodeOutcome(ToolResult.failure((node.metric() == null ? "工具执行异常: " : "指标查询失败: ") + e.getMessage()),
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

    /** 主线程回收：带超时等待 → 写 DagContext → 落 trace；REUSE_FACT 节点落 FACT_REUSED，不伪装成 METRIC_RESULT。 */
    private void collect(PlanNode node, Future<NodeOutcome> future, NodeTraceCollector.Session session,
                         DagContext ctx,
                         String traceId, TraceSink sink,
                         com.cexpilot.metric.ExecutionBindings bindings) {
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
        // 节点执行期间的子调用（如 MCP tools/call）先于节点自身事件落库，保持时序。
        // 冻结后取快照：节点超时场景下工作线程可能仍存活，迟到写入被丢弃，主线程不并发遍历。
        for (NodeTraceCollector.Draft draft : session.freezeAndSnapshot()) {
            sink.record(TraceEvent.toolCall(traceId, draft.name(), draft.inputJson(),
                    draft.outputJson(), draft.durationMs(), draft.error()));
        }
        com.cexpilot.metric.ExecutionBindings.ReuseFact reuse =
                node.metric() != null && bindings != null ? bindings.reuseFor(node.id()) : null;
        if (reuse != null) {
            // 复用事实保留来源轮次与观测时刻；不写 METRIC_RESULT，保持其"本轮真实 API 取数"语义纯净
            ObjectNode input = MAPPER.createObjectNode();
            input.put("node_id", node.id());
            input.put("fact_id", reuse.factId());
            input.put("source_turn_id", reuse.sourceTurnId());
            if (reuse.observedAt() != null) input.put("observed_at", reuse.observedAt());
            input.put("fetched_at", reuse.fetchedAt());
            input.put("reference_mode", "PINNED_RESULT");
            input.set("metric_binding", node.metric().identity());
            sink.record(new TraceEvent(traceId, "FACT_REUSED", node.metric().metric(),
                    input.toString(), result.toMessageContent(), outcome.durationMs(),
                    null, null, null, null, result.error()));
        } else if (node.metric() != null) {
            // Provider 已直接返回指标模型；没有原始 Tool 调用，也不进行第二次适配。
            ObjectNode input = MAPPER.createObjectNode();
            input.put("node_id", node.id());
            input.set("args", node.args());
            input.set("metric_binding", node.metric().identity());
            sink.record(new TraceEvent(traceId, "METRIC_RESULT", node.metric().metric(),
                    input.toString(), result.toMessageContent(), outcome.durationMs(),
                    null, null, null, null, result.error()));
        } else {
            sink.record(TraceEvent.toolCall(traceId, node.tool(),
                    eventInput(node.id(), outcome.resolvedArgsJson()),
                    result.toMessageContent(), outcome.durationMs(),
                    result.ok() ? null : result.error()));
        }
        ctx.put(node.id(), result);
    }

    private static String executionName(PlanNode node) {
        return node.metric() == null ? node.tool() : node.metric().provider();
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
