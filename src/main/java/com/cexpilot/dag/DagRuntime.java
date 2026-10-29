package com.cexpilot.dag;

import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.LlmTraceSerializer;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * DAG 运行时（替代原 ReAct 循环）：一次问答 = DagPlanner 合并调用（领域判断 + 规划）→ DagExecutor 并行执行 → 汇总 evidence → 1 次不带工具的 LLM 调用生成最终回答。
 *
 * 工具返回的完整 evidence 直接作为回答的 FACTS，不在回答前删除明细；统一按问题与证据适用事实约束。
 * 出域直接返回边界话术；planner 有意不规划且给出 reply（追问/能力缺口）时直接透传为答案；
 * 规划失败或没有计划时也直接返回，禁止再让 Answer 用空事实生成答案。
 */
@Component
public class DagRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(DagRuntime.class);
    private static final String ANSWER_PROMPT_NAME = "agent_system";
    // 与行情工具未携带用户时区时的默认口径一致，不使用服务器本地时区。
    private static final ZoneId DEFAULT_USER_ZONE = ZoneOffset.ofHours(8);
    private static final String OUT_OF_DOMAIN_FALLBACK =
            "我只支持 web3 / 加密货币交易领域的问题，暂时无法回答其他类型的问题。";

    private final LlmClient llm;
    private final DagPlanner planner;
    private final DagExecutor executor;
    private final PromptStore prompts;
    private final Clock clock;
    // 阶段二：历史事实存取；null 时跳过事实保存与 REUSE_FACT 绑定（单轮旧行为）
    private final com.cexpilot.evidence.EvidenceStore evidenceStore;

    public DagRuntime(LlmClient llm, DagPlanner planner, DagExecutor executor, PromptStore prompts,
                      Clock clock) {
        this(llm, planner, executor, prompts, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DagRuntime(@org.springframework.beans.factory.annotation.Qualifier(com.cexpilot.config.LlmClientConfig.NORMAL_LLM_CLIENT) LlmClient llm,
                      DagPlanner planner, DagExecutor executor, PromptStore prompts,
                      Clock clock, com.cexpilot.evidence.EvidenceStore evidenceStore) {
        this.llm = llm;
        this.planner = planner;
        this.executor = executor;
        this.prompts = prompts;
        this.clock = clock;
        this.evidenceStore = evidenceStore;
    }

    public ExecutionResult execute(String question, String conversationContext, String traceId, TraceSink sink) {
        return execute(question, conversationContext, traceId, sink, null, null);
    }

    /**
     * @param answerDelta 非 null 时 answer 阶段走流式调用，逐段回调答案增量（用于 SSE 推送）；
     *                    trace 落库与聚合逻辑不变。
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta) {
        return execute(question, conversationContext, traceId, sink, answerDelta, null);
    }

    /**
     * @param requestContext 请求时间上下文（用户时区 + 固定 requestTime），随 ToolContext
     *                       传给每个工具节点和 Answer；缺省值在规划前统一补齐
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, null);
    }

    /**
     * @param progress 非 null 时在阶段边界回调进度文案（SSE 推给前端展示"执行到哪一步"）
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, progress, null);
    }

    /**
     * @param compiledPlanSink 非 null 时，计划通过校验后、执行前回调完整编译产物（含 LogicalPlan 与
     *                         有效条件），供轮次/QueryFrame 持久化；不影响任何执行行为
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress,
                                   Consumer<com.cexpilot.metric.CompiledPlan> compiledPlanSink) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, progress,
                compiledPlanSink, null);
    }

    /**
     * @param plannerInput 结构化 Planner 输入（阶段一 c）：contextText 只注入 planner prompt，
     *                     activeFrame 供继承对账；Answer 侧仍用 conversationContext 纯文本，不受影响。
     *                     null 时 planner 沿用 conversationContext（旧行为）
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress,
                                   Consumer<com.cexpilot.metric.CompiledPlan> compiledPlanSink,
                                   com.cexpilot.conversation.PlannerInput plannerInput) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, progress,
                compiledPlanSink, plannerInput, null);
    }

    /**
     * @param evidenceSession 事实保存会话（阶段二）：非 null 且有 EvidenceStore 时，
     *                        执行后、Answer 前把本轮取数结果保存为 FACT（Answer 失败也已保存，F13），
     *                        并启用 pinned_result → REUSE_FACT 绑定
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress,
                                   Consumer<com.cexpilot.metric.CompiledPlan> compiledPlanSink,
                                   com.cexpilot.conversation.PlannerInput plannerInput,
                                   com.cexpilot.evidence.EvidenceSession evidenceSession) {
        RequestContext effectiveContext = resolveRequestContext(requestContext);
        ObjectNode timeContext = answerTimeContext(effectiveContext,
                requestContext != null && requestContext.userZone() != null);
        String plannerContext = plannerInput != null && plannerInput.contextText() != null
                ? plannerInput.contextText() : conversationContext;
        com.cexpilot.conversation.QueryFrame activeFrame = plannerInput != null ? plannerInput.activeFrame() : null;
        com.cexpilot.evidence.EvidenceBindingContext evidenceContext =
                plannerInput != null && evidenceSession != null && evidenceStore != null
                        ? new com.cexpilot.evidence.EvidenceBindingContext(
                                evidenceSession.conversationId(), plannerInput.evidence(), evidenceStore)
                        : null;
        emit(progress, "正在生成执行计划…");
        DagPlanner.PlanOutcome outcome;
        try {
            outcome = planner.plan(question, plannerContext, traceId, sink,
                    activeFrame, effectiveContext, evidenceContext,
                    plannerInput == null ? List.of() : plannerInput.tasks(),
                    plannerInput == null ? null : plannerInput.currentTask());
        } catch (DagPlanner.PlanningFailure pf) {
            // 4.3：planner 阶段失败（LLM 欠费/超时）同样带出已发生的调用计数
            throw new ExecutionFailure(pf, new TurnCounters(pf.llmCalls, 0, 0, 0, 0, 0, List.of())
                    .withLlmUsages(pf.llmUsages), pf.taskScope);
        }
        if (outcome.taskScope() != null) {
            effectiveContext = outcome.taskScope().executionContext(effectiveContext);
            timeContext = answerTimeContext(effectiveContext, requestContext != null && requestContext.userZone() != null);
        }
        int totalPromptTokens = outcome.promptTokens();
        int totalCompletionTokens = outcome.completionTokens();
        int plannerLlmCalls = outcome.llmCalls();
        List<LlmUsage> usages = new ArrayList<>(outcome.llmUsages());

        if (!outcome.inDomain()) {
            String answer = outcome.reply() == null || outcome.reply().isBlank()
                    ? OUT_OF_DOMAIN_FALLBACK : outcome.reply();
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens, null, null, 0,
                    plannerLlmCalls, 0, 0, 0, 0, List.of()).withTaskScope(outcome.inDomain() ? outcome.taskScope() : null).withLlmUsages(usages);
        }

        // 澄清：不执行、不落 frame；编排层把轮次标 NEEDS_CLARIFICATION 并把澄清问题写入 pendingClarification
        if (outcome.needsClarification()) {
            String answer = outcome.reply() == null || outcome.reply().isBlank()
                    ? "这个问题我需要再确认一下，请补充说明。" : outcome.reply();
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            ObjectNode clarification = MAPPER.createObjectNode().put("status", "NEEDS_CLARIFICATION");
            clarification.set("clarify", MAPPER.valueToTree(outcome.clarificationFields()));
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens, "NEEDS_CLARIFICATION", outcome.reply(), 0,
                    plannerLlmCalls, 0, 0, 0, 0, List.of()).withTaskScope(outcome.inDomain() ? outcome.taskScope() : null).withLlmUsages(usages).withAgent("query", clarification);
        }

        // 没有获准执行的计划，就没有 Answer 调用；拒答也走同一个 SSE 返回路径。
        if (outcome.plan().isEmpty()) {
            String answer = outcome.lastError() != null
                    ? outcome.reply() != null && !outcome.reply().isBlank() ? outcome.reply()
                    : "暂时无法可靠生成查询计划，本次未执行查询。请明确交易对和时间要求后重试。"
                    : outcome.reply() != null && !outcome.reply().isBlank() ? outcome.reply()
                    : "当前没有能够可靠回答此问题的查询计划，暂时无法处理。";
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens, outcome.lastError() == null ? null : "FAILED", null, 0,
                    plannerLlmCalls, 0, 0, 0, 0, List.of()).withTaskScope(outcome.inDomain() ? outcome.taskScope() : null).withLlmUsages(usages);
        }

        ArrayNode evidence = MAPPER.createArrayNode();
        int toolCallCount = 0;
        int steps = 0;

        ObjectNode queryStatus = MAPPER.createObjectNode();
        // reply 是 planner 写的查询缺口说明；模型偶尔会把推理过程倒进来，截断防污染
        if (outcome.reply() != null) {
            String missing = outcome.reply();
            queryStatus.put("missing", missing.length() <= 200 ? missing : missing.substring(0, 200));
        }
        DagPlan plan = outcome.plan().orElseThrow().dagPlan();
        com.cexpilot.metric.ExecutionBindings bindings = outcome.bindings();
        if (compiledPlanSink != null) {
            compiledPlanSink.accept(outcome.plan().orElseThrow());
        }
        emit(progress, "执行计划已生成（" + plan.nodes().size() + " 项查询），正在获取数据…");
        boolean answerAttempted = false;
        Map<String, String> savedFactIds = new java.util.HashMap<>();
        List<String> newFactIds = new ArrayList<>();
        int reusedFactCount = 0;
        int metricFetchCount = 0;
        int calculationCount = 0;
        try {
            DagExecutor.ExecutionOutcome execution = executor.execute(plan, traceId, sink, effectiveContext, bindings);
            emit(progress, "数据获取完毕，正在组装答案…");
            steps = execution.layers();
            for (PlanNode node : plan.nodes()) {
                ToolResult result = execution.context().get(node.id());
                if (result == null) {
                    continue;
                }
                toolCallCount++;
                boolean reusedNode = bindings != null && bindings.reuseFor(node.id()) != null;
                // 计数拆分（阶段三）：REUSE_FACT 节点不算真实取数；算子节点只在真正执行时计数
                // （4.3 口径：上游失败被跳过的节点不计数）
                if (node.metric() != null && !reusedNode) {
                    metricFetchCount++;
                } else if (node.metric() == null && result.ok()) {
                    calculationCount++;
                }
                // 事实保存在 Answer 之前：即使 Answer 失败，本轮已取事实也已落库（F13）
                if (evidenceSession != null && evidenceStore != null) {
                    Map<String, String> saved = saveFacts(outcome.plan().orElseThrow(), node, result, bindings,
                            savedFactIds, evidenceSession, traceId, effectiveContext.requestTime());
                    savedFactIds.putAll(saved);
                    if (!reusedNode) {
                        // REUSE_FACT 沿用历史 factId，不算本轮新保存
                        newFactIds.addAll(saved.values());
                    }
                }
                appendEvidence(evidence, node, result, bindings, savedFactIds);
                if (reusedNode && result.ok()) {
                    reusedFactCount++;
                }
            }
            String userContent = question + "\n\n<FACTS>\n" + evidence + "\n</FACTS>\n<QUERY_STATUS>\n"
                    + queryStatus + "\n</QUERY_STATUS>";

            String systemPrompt = prompts.render(ANSWER_PROMPT_NAME, Map.of(
                    "time_context", timeContext.toString(),
                    "conversation_context", conversationContext == null ? "" : conversationContext));
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(systemPrompt));
            messages.add(ChatMessage.user(userContent));
            answerAttempted = true;
            ChatResponse answer = callAnswerLlm(messages, traceId, sink, answerDelta, timeContext, usages);
            totalPromptTokens += answer.promptTokens() == null ? 0 : answer.promptTokens();
            totalCompletionTokens += answer.completionTokens() == null ? 0 : answer.completionTokens();

            return new ExecutionResult(answer.content(), evidence, toolCallCount, steps,
                    totalPromptTokens, totalCompletionTokens, null, null, reusedFactCount,
                    plannerLlmCalls, 1, metricFetchCount, calculationCount, execution.httpRequests(),
                    newFactIds).withTaskScope(outcome.taskScope()).withLlmUsages(usages);
        } catch (Exception e) {
            // 4.3：失败不等于统计归零——把已发生的调用计数与已保存事实随异常带出，
            // 编排层失败路径据此落 trace，耗时/成本分析才有完整口径
            throw new ExecutionFailure(e, new TurnCounters(plannerLlmCalls, answerAttempted ? 1 : 0,
                    metricFetchCount, calculationCount, 0, reusedFactCount, List.copyOf(newFactIds))
                    .withLlmUsages(usages), outcome.taskScope(), evidence);
        }
    }

    /** 失败路径的调用/事实计数快照（4.3）。answerLlmCalls 为尝试次数（1=已发起）。 */
    public record TurnCounters(int plannerLlmCalls, int answerLlmCalls, int metricFetchCount,
                               int calculationCount, int httpRequestCount, int reusedFactCount,
                               List<String> savedFactIds, List<LlmUsage> llmUsages,int routerLlmCalls) {

        public TurnCounters(int plannerLlmCalls,int answerLlmCalls,int metricFetchCount,int calculationCount,
                int httpRequestCount,int reusedFactCount,List<String> savedFactIds,List<LlmUsage> usages) {
            this(plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,reusedFactCount,savedFactIds,usages,0);
        }

        public TurnCounters(int plannerLlmCalls, int answerLlmCalls, int metricFetchCount,
                int calculationCount, int httpRequestCount, int reusedFactCount, List<String> savedFactIds) {
            this(plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount, httpRequestCount,
                    reusedFactCount, savedFactIds, null);
        }

        public TurnCounters withLlmUsages(List<LlmUsage> usages) {
            return new TurnCounters(plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount,
                    httpRequestCount, reusedFactCount, savedFactIds, usages,routerLlmCalls);
        }

        public TurnCounters withRouterUsages(List<LlmUsage> routing) {
            var combined=new ArrayList<>(routing);
            if(llmUsages!=null) combined.addAll(llmUsages);
            else if(plannerLlmCalls+answerLlmCalls>0) combined.add(new LlmUsage("unknown",null,null,null,null));
            return new TurnCounters(plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,
                    reusedFactCount,savedFactIds,combined,routing.size());
        }

        public TurnCounters {
            savedFactIds = savedFactIds == null ? List.of() : List.copyOf(savedFactIds);
            llmUsages = llmUsages == null ? null : List.copyOf(llmUsages);
        }
    }

    /** 包装执行期异常并携带 {@link TurnCounters}；编排层解包后用原始异常落 trace。 */
    public static final class ExecutionFailure extends RuntimeException {

        public final TurnCounters counters;
        public final com.cexpilot.conversation.TaskScope taskScope;
        public final JsonNode evidence;

        public ExecutionFailure(Throwable cause, TurnCounters counters) { this(cause, counters, null); }
        public ExecutionFailure(Throwable cause, TurnCounters counters, com.cexpilot.conversation.TaskScope taskScope) {
            this(cause, counters, taskScope, null);
        }
        public ExecutionFailure(Throwable cause, TurnCounters counters, com.cexpilot.conversation.TaskScope taskScope, JsonNode evidence) {
            super(cause.getMessage(), cause);
            this.counters = counters;
            this.taskScope = taskScope;
            this.evidence = evidence == null ? null : evidence.deepCopy();
        }
    }

    /**
     * 单节点事实保存：metric 节点存原始 FACT（REUSE_FACT 节点不重复入库，沿用原 factId）；
     * 计算节点存派生 FACT（derived=true + sourceFactIds，不作为样本集来源）。
     * 返回本节点 nodeId→factId 映射（供下游派生事实记录来源与 evidence 标注）。保存失败只记日志。
     */
    private Map<String, String> saveFacts(com.cexpilot.metric.CompiledPlan compiled, PlanNode node,
                                          ToolResult result, com.cexpilot.metric.ExecutionBindings bindings,
                                          Map<String, String> savedFactIds,
                                          com.cexpilot.evidence.EvidenceSession session,
                                          String traceId, java.time.Instant now) {
        if (!result.ok() || result.data() == null) {
            return Map.of();
        }
        try {
            if (node.metric() != null) {
                if (bindings != null && bindings.reuseFor(node.id()) != null) {
                    return Map.of(node.id(), bindings.reuseFor(node.id()).factId());
                }
                com.cexpilot.metric.EffectiveTarget target = compiled.targets().stream()
                        .filter(t -> t.physicalId().equals(node.id())).findFirst().orElse(null);
                if (target == null) {
                    return Map.of();
                }
                return evidenceStore.saveFact(session.conversationId(), session.turnId(), traceId,
                                target, result.data(), now)
                        .map(f -> Map.of(node.id(), f.factId())).orElse(Map.of());
            }
            if (node.tool() != null) {
                List<String> sourceFactIds = node.dependsOn().stream()
                        .map(dep -> savedFactIds.get(dep)).filter(Objects::nonNull).distinct().toList();
                return evidenceStore.saveDerivedFact(session.conversationId(), session.turnId(), traceId,
                                node.tool(), result.data(), sourceFactIds, now)
                        .map(f -> Map.of(node.id(), f.factId())).orElse(Map.of());
            }
        } catch (Exception e) {
            log.warn("事实保存失败（不影响本轮回答）node={}: {}", node.id(), e.getMessage());
        }
        return Map.of();
    }

    private static void emit(Consumer<String> progress, String text) {
        if (progress != null) {
            progress.accept(text);
        }
    }

    private ChatResponse callAnswerLlm(List<ChatMessage> messages, String traceId, TraceSink sink,
                                       Consumer<String> answerDelta, ObjectNode timeContext, List<LlmUsage> usages) {
        long start = System.currentTimeMillis();
        boolean received = false;
        try {
            ChatResponse response = answerDelta == null
                    ? llm.chat(messages, null)
                    : llm.chatStream(messages, null, answerDelta);
            LlmUsage usage = planner.answerUsage(response);
            usages.add(usage);
            received = true;
            ObjectNode output = LlmTraceSerializer.responseToObject(response);
            output.put("model_role", "normal").put("model", usage.model());
            output.set("estimated_cost", MAPPER.valueToTree(usage.estimatedCost()));
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(messages, timeContext), output.toString(),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(),
                    response.ttftMs(), response.cachedTokens(), null));
            return response;
        } catch (Exception e) {
            if (!received) usages.add(planner.answerUsage(null));
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(messages, timeContext), null,
                    System.currentTimeMillis() - start, null, null, null, null, e.getMessage()));
            throw e;
        }
    }

    private RequestContext resolveRequestContext(RequestContext context) {
        return new RequestContext(
                context != null && context.userZone() != null ? context.userZone() : DEFAULT_USER_ZONE,
                context != null && context.requestTime() != null ? context.requestTime() : clock.instant());
    }

    private static ObjectNode answerTimeContext(RequestContext context, boolean userZoneProvided) {
        var localTime = context.requestTime().atZone(context.userZone());
        ObjectNode node = MAPPER.createObjectNode();
        node.put("request_time_utc", context.requestTime().toString());
        node.put("timezone", context.userZone().getId());
        node.put("timezone_source", userZoneProvided ? "request" : "default");
        node.put("request_time_local", localTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        node.put("current_date", localTime.toLocalDate().toString());
        return node;
    }

    private void appendEvidence(ArrayNode evidence, PlanNode node, ToolResult result,
                                com.cexpilot.metric.ExecutionBindings bindings,
                                Map<String, String> savedFactIds) {
        ObjectNode entry = evidence.addObject();
        entry.put("node_id", node.id());
        if (node.metric() != null) {
            entry.put("type", "metric");
            entry.set("identity", node.metric().identity());
        } else {
            entry.put("type", "calculation");
            entry.put("operator", node.tool());
            entry.set("input_references", node.args());
        }
        entry.put("ok", result.ok());
        String factId = savedFactIds.get(node.id());
        if (factId != null) {
            entry.put("fact_id", factId);
        }
        com.cexpilot.metric.ExecutionBindings.ReuseFact reuse =
                bindings == null ? null : bindings.reuseFor(node.id());
        if (reuse != null) {
            // 复用事实的口径标注：Answer 必须说明数据时间点，不得当成本轮新取
            entry.put("reused", true);
            entry.put("source_turn_id", reuse.sourceTurnId());
            if (reuse.observedAt() != null) entry.put("observed_at", reuse.observedAt());
            entry.put("fetched_at", reuse.fetchedAt());
        }
        if (result.data() != null) {
            entry.set("data", result.data());
        }
        if (result.error() != null) {
            entry.put("error", result.error());
        }
    }

    private String answerEventInput(List<ChatMessage> messages, ObjectNode timeContext) {
        ObjectNode node = MAPPER.createObjectNode();
        llm.requestParameters(null).fields().forEachRemaining(field -> node.set(field.getKey(), field.getValue()));
        node.put("stage", "answer");
        node.put("model_role", "normal");
        if (!node.hasNonNull("model")) node.put("model", planner.answerUsage(null).model());
        node.put("message_count", messages.size());
        node.set("time_context", timeContext);
        node.set("messages", LlmTraceSerializer.messagesToArray(messages));
        return node.toString();
    }

    public String promptVersion() {
        return PromptStore.fingerprint(planner.promptVersion() + prompts.version(ANSWER_PROMPT_NAME));
    }
}
