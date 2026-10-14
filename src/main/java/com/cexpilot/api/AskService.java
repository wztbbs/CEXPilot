package com.cexpilot.api;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.conversation.ConversationService;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.trace.DbTraceSink;
import com.cexpilot.trace.TraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 一次问答的编排：对话上下文 → 建 trace → DagRuntime（一次 LLM 调用完成领域判断 + 查询规划，随后执行 DAG 并生成回答）→ 落 trace / 记 Query。
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final ConversationService conversation;
    private final com.cexpilot.conversation.ConversationContextService contextService;
    private final DagRuntime runtime;
    private final TraceRepository traceRepository;
    private final DbTraceSink traceSink;
    private final LlmConfig llmConfig;
    private final Clock clock;
    // 构建时打入的 git commit 指纹；IDE 直跑（无 build-info.properties）时为 null
    private final String buildCommit;

    public AskService(ConversationService conversation,
                      com.cexpilot.conversation.ConversationContextService contextService,
                      DagRuntime runtime,
                      TraceRepository traceRepository,
                      DbTraceSink traceSink,
                      LlmConfig llmConfig,
                      Clock clock,
                      ObjectProvider<BuildProperties> buildProperties) {
        this.conversation = conversation;
        this.contextService = contextService;
        this.runtime = runtime;
        this.traceRepository = traceRepository;
        this.traceSink = traceSink;
        this.llmConfig = llmConfig;
        this.clock = clock;
        BuildProperties props = buildProperties.getIfAvailable();
        this.buildCommit = props != null ? props.get("commit") : null;
    }

    /** 流式问答的事件出口：meta 在建 trace 后触发，progress 为阶段进度，delta 为答案增量，done 带完整结果。 */
    public interface AskStreamListener {
        /** 失败也可能已经提交为完成轮次；仅在收尾成功后确认，客户端才推进父轮次。 */
        default void onTurnFinished(String turnId) {}
        void onMeta(String conversationId, String traceId, String turnId);

        void onProgress(String text);

        void onDelta(String text);

        void onDone(AskResponse response);
    }

    public AskResponse ask(String conversationId, String question) {
        return ask(conversationId, question, null, null, null, null);
    }

    /**
     * 一次问答的完整编排。这里只做流程串联：领域判断、查询规划与 DAG 执行在 DagRuntime
     * 里，计算逻辑在各工具内部。
     *
     * @param visitorId 访客标识（cexpilot_uid cookie），随 trace 落库用于统计；允许为 null
     */
    public AskResponse ask(String conversationId, String question, String visitorId) {
        return ask(conversationId, question, visitorId, null, null, null);
    }

    /**
     * @param listener 非 null 时走流式：建 trace 后立刻回调 meta，answer 阶段逐段回调 delta，
     *                 完成后回调 done。trace 落库与非流式路径完全一致。
     */
    public AskResponse ask(String conversationId, String question, String visitorId,
                           AskStreamListener listener) {
        return ask(conversationId, question, visitorId, null, null, listener);
    }

    /**
     * @param timezone 请求携带的 IANA 时区（如 Asia/Shanghai），作为时间消解的请求上下文；
     *                 null 或空表示未携带，由各工具按默认口径回落
     */
    public AskResponse ask(String conversationId, String question, String visitorId, String timezone,
                           AskStreamListener listener) {
        return ask(conversationId, question, visitorId, timezone, null, listener);
    }

    /**
     * @param parentTurnId 页面看到的最近完成轮次，登记前校验；null 兼容旧客户端
     */
    public AskResponse ask(String conversationId, String question, String visitorId, String timezone,
                           String parentTurnId, AskStreamListener listener) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        // 请求开始即固定时间基准：同一次问答里所有工具节点共用同一个“现在”，
        // 不允许各环节各自读取时钟（跨午夜会导致“昨天”在不同节点变成不同日期）
        RequestContext requestContext = new RequestContext(parseZone(timezone), clock.instant());

        // 1. 落定对话：conversationId 为空则新建对话；不为空则沿用（多轮追问的载体）。
        //    对话只保留最近几个 Query，不做长期记忆。
        String resolvedConversationId = conversation.getOrCreateConversation(conversationId, question, visitorId);

        // 2. 登记轮次（短事务）：原子分配 query_no + turnId、占用会话执行权、写入 RUNNING 行。
        //    同会话已有未过期的执行中请求时返回明确的忙碌响应（首期串行约束），不建 trace、不执行 DAG。
        ConversationService.TurnHandle turn = conversation.beginTurn(resolvedConversationId, question,
                parentTurnId, requestContext.requestTime());
        if (turn == null) {
            AskResponse busy = new AskResponse(resolvedConversationId, null,
                    "上一个问题还在处理中，请稍后再试", null, 0, 0, 0, null, true);
            if (listener != null) {
                listener.onMeta(resolvedConversationId, null, null);
                listener.onDone(busy);
            }
            return busy;
        }

        // 3. 取得轮次后立即进入 try/finally（R8）：上下文读取、建 trace、SSE meta 任何一步失败
        //    都要走到轮次收尾，不留 RUNNING 行和执行占用卡死会话。
        long start = System.currentTimeMillis();
        boolean turnFinished = false;
        boolean traceStarted = false;
        boolean traceFinished = false;
        String traceId = UUID.randomUUID().toString();
        // 计划通过校验后（执行前）由 runtime 回调，成功路径用它提交 frame，失败路径用它落 logical_plan_json
        java.util.concurrent.atomic.AtomicReference<com.cexpilot.metric.CompiledPlan> compiledPlanRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        var taskScopeRef = new java.util.concurrent.atomic.AtomicReference<com.cexpilot.conversation.TaskScope>(
                new com.cexpilot.conversation.TaskScope(com.cexpilot.conversation.TaskScope.Action.NEW, null));
        var failureEvidenceRef = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        try {
            // 4. 渲染对话上下文：Answer 侧仍用 renderContext 纯文本（口径不变）；
            //    Planner 侧用分层结构化上下文（活动 frame + 待补任务/失败尝试 + 带时间的近期问答）。
            String conversationContext = conversation.renderContext(resolvedConversationId);
            com.cexpilot.conversation.PlannerInput plannerInput =
                    contextService.buildPlannerInput(resolvedConversationId);
            taskScopeRef.set(com.cexpilot.conversation.TaskScope.current(plannerInput.currentTask()));

            // 5. 开启 trace：写入一条 RUNNING 记录。traceId 是本次问答的全局标识——
            //    后续每次 LLM 调用、每次工具调用都会以它为外键落 trace_event，
            //    用户点 👎 后凭它完整回放"当时调了什么工具、拿到了什么数据"。
            //    同时记录 model、promptVersion（prompt 内容的哈希）和 buildCommit（代码版本指纹），
            //    便于排查答案可复现性。
            traceRepository.startTrace(traceId, resolvedConversationId, question,
                    llmConfig.getNormal().getModel(), runtime.promptVersion(), visitorId, buildCommit);
            traceStarted = true;
            if (listener != null) {
                listener.onMeta(resolvedConversationId, traceId, turn.turnId());
            }
            // CONTEXT_RESOLVED（阶段三观测）：Planner 输入组装完成后的上下文摘要
            traceSink.record(new com.cexpilot.runtime.TraceEvent(traceId, "CONTEXT_RESOLVED", "context_service",
                    null, contextResolvedSummary(resolvedConversationId, turn, plannerInput),
                    null, null, null, null, null, null));

            // 6. 执行 DAG（合并规划 → 并行执行 → 生成回答），成功后收尾：trace 置为 SUCCESS，
            //    记录答案、层数、工具调用数、token 用量与成本；
            //    轮次提交 SUCCEEDED（补 answer/trace_id/finished_at、释放执行占用），成为后续追问的上下文。
            ExecutionResult result = runtime.execute(question, conversationContext, traceId, traceSink,
                    listener == null ? null : listener::onDelta, requestContext,
                    listener == null ? null : listener::onProgress, compiledPlanRef::set, plannerInput,
                    new com.cexpilot.evidence.EvidenceSession(resolvedConversationId, turn.turnId()));
            long durationMs = System.currentTimeMillis() - start;
            Double cost = result.llmUsages() == null ? computeCost(result.promptTokens(), result.completionTokens())
                    : com.cexpilot.llm.LlmUsage.totalCost(result.llmUsages());

            traceRepository.finishTrace(traceId, "SUCCESS", result.answer(), result.steps(),
                    result.toolCallCount(), result.promptTokens(), result.completionTokens(),
                    cost, durationMs, null, result.reusedFactCount(),
                    result.plannerLlmCalls(), result.answerLlmCalls(), result.metricFetchCount(),
                    result.calculationCount(), result.httpRequestCount());
            traceFinished = true;

            String turnStatus = result.turnStatus() != null ? result.turnStatus() : "SUCCEEDED";
            com.cexpilot.metric.CompiledPlan compiledPlan = compiledPlanRef.get();
            var scope = result.taskScope();
            if (scope == null && (compiledPlan != null || "NEEDS_CLARIFICATION".equals(turnStatus)
                    || "FAILED".equals(turnStatus))) scope = taskScopeRef.get();
            if (scope != null) taskScopeRef.set(scope);
            var selectedContext = scope == null ? requestContext : scope.executionContext(requestContext);
            var commit = conversation.finishTaskTurn(resolvedConversationId, turn, question, turnStatus,
                    result.answer(), traceId, compiledPlan, result.evidence(), scope,
                    selectedContext.requestTime(), selectedContext.userZone(),
                    result.clarify() != null ? result.clarify() : "FAILED".equals(turnStatus) ? result.answer() : null);
            boolean frameUpdated = commit.updated();
            turnFinished = true;
            // CONTEXT_COMMITTED（阶段三观测）：轮次状态提交后的最终状态摘要（frame_updated 为真实写入结果）
            traceSink.record(new com.cexpilot.runtime.TraceEvent(traceId, "CONTEXT_COMMITTED", "ask_service",
                    null, contextCommittedSummary(turnStatus, result.savedFactIds(), frameUpdated),
                    null, null, null, null, null, null));

            // 7. evidence（工具产出的事实集）随答案一起返回，前端可展示"答案引用了哪些数据"。
            AskResponse response = new AskResponse(resolvedConversationId, traceId, result.answer(),
                    result.evidence(), result.toolCallCount(), result.steps(), durationMs, turn.turnId(), false,
                    result.plannerLlmCalls(), result.answerLlmCalls(), result.metricFetchCount(),
                    result.calculationCount(), result.httpRequestCount(), result.reusedFactCount());
            if (listener != null) {
                try {
                    listener.onDone(response);
                } catch (Exception ex) {
                    // 答案与轮次状态已提交成功，SSE 推送失败只影响本次连接，不得把轮次改写成 FAILED（R8）
                    log.warn("onDone 推送失败（答案已提交）turnId={}: {}", turn.turnId(), ex.getMessage());
                }
            }
            return response;
        } catch (Exception e) {
            // 失败同样要落 trace 与轮次：失败的现场数据正是后续定位问题、固化评测 case 的原料。
            long durationMs = System.currentTimeMillis() - start;
            // 4.3：DagRuntime 的 ExecutionFailure 携带已发生的调用计数与已保存事实——失败不统计归零
            Exception failure = e;
            DagRuntime.TurnCounters counters = null;
            if (e instanceof DagRuntime.ExecutionFailure f) {
                failure = f.getCause() instanceof Exception ex ? ex : e;
                counters = f.counters;
                if (f.taskScope != null) taskScopeRef.set(f.taskScope);
                failureEvidenceRef.set(f.evidence);
            }
            log.warn("问答失败 traceId={}: {}", traceId, failure.getMessage());
            if (traceStarted && !traceFinished) {
                traceRepository.finishTrace(traceId, "FAILED", null, 0, 0,
                        counters == null || counters.llmUsages() == null ? null
                                : com.cexpilot.llm.LlmUsage.knownPromptTokens(counters.llmUsages()),
                        counters == null || counters.llmUsages() == null ? null
                                : com.cexpilot.llm.LlmUsage.knownCompletionTokens(counters.llmUsages()),
                        counters == null || counters.llmUsages() == null ? null
                                : com.cexpilot.llm.LlmUsage.totalCost(counters.llmUsages()), durationMs, failure.getMessage(),
                        counters == null ? null : counters.reusedFactCount(),
                        counters == null ? null : counters.plannerLlmCalls(),
                        counters == null ? null : counters.answerLlmCalls(),
                        counters == null ? null : counters.metricFetchCount(),
                        counters == null ? null : counters.calculationCount(),
                        counters == null ? null : counters.httpRequestCount());
            }
            if (!turnFinished) {
                com.cexpilot.metric.CompiledPlan compiledPlan = compiledPlanRef.get();
                turnFinished = failTurnQuietly(resolvedConversationId, turn, question, traceId,
                        compiledPlan, taskScopeRef.get(), requestContext, failure.getMessage(), failureEvidenceRef.get(), traceStarted,
                        counters == null ? java.util.List.of() : counters.savedFactIds());
            }
            if (turnFinished && listener != null) {
                try { listener.onTurnFinished(turn.turnId()); }
                catch (Exception ignored) { /* 失败已提交，不因通知失败改写状态。 */ }
            }
            throw e;
        } finally {
            // 兜底释放执行占用（如 catch 里收尾也失败、或抛出的是 Error），避免会话被永久锁死
            if (!turnFinished) {
                com.cexpilot.metric.CompiledPlan compiledPlan = compiledPlanRef.get();
                failTurnQuietly(resolvedConversationId, turn, question, traceId,
                        compiledPlan, taskScopeRef.get(), requestContext, "请求未完成", failureEvidenceRef.get(), traceStarted, java.util.List.of());
            }
        }
    }

    /**
     * 失败收尾：在所选任务上保存失败版本、有效计划和事实焦点，保留最近成功状态并释放占用；
     * CONTEXT_COMMITTED 记录失败前已保存的事实（4.3）；自身再失败只记日志，不掩盖原始异常。返回是否完成收尾。
     */
    private boolean failTurnQuietly(String conversationId, ConversationService.TurnHandle turn, String question,
                                    String traceId, com.cexpilot.metric.CompiledPlan compiledPlan,
                                    com.cexpilot.conversation.TaskScope scope, RequestContext requestContext,
                                    String failureMessage, com.fasterxml.jackson.databind.JsonNode failureEvidence,
                                    boolean traceStarted, java.util.List<String> savedFactIds) {
        try {
            var selected = scope.executionContext(requestContext);
            conversation.finishTaskTurn(conversationId, turn, question, "FAILED", null, traceId, compiledPlan,
                    failureEvidence, scope, selected.requestTime(), selected.userZone(), failureMessage);
            if (traceStarted) {
                traceSink.record(new com.cexpilot.runtime.TraceEvent(traceId, "CONTEXT_COMMITTED", "ask_service",
                        null, contextCommittedSummary("FAILED", savedFactIds, false),
                        null, null, null, null, null, null));
            }
            return true;
        } catch (Exception ex) {
            log.warn("轮次失败收尾失败 turnId={}: {}", turn.turnId(), ex.getMessage());
            return false;
        }
    }

    /** CONTEXT_RESOLVED 的 output_json：轮次序号、活动 frame 摘要（targets 关键条件）、可引用事实候选数、文本历史条数。 */
    private static String contextResolvedSummary(String conversationId, ConversationService.TurnHandle turn,
                                                 com.cexpilot.conversation.PlannerInput plannerInput) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var out = mapper.createObjectNode();
        out.put("conversation_id", conversationId);
        out.put("query_no", turn.queryNo());
        com.cexpilot.conversation.QueryFrame frame = plannerInput == null ? null : plannerInput.activeFrame();
        if (frame == null) {
            out.putNull("active_frame");
        } else {
            var frameNode = out.putObject("active_frame");
            frameNode.put("source_turn_id", frame.sourceTurnId());
            var targets = frameNode.putArray("targets");
            for (com.cexpilot.metric.EffectiveTarget t : frame.targets()) {
                var target = targets.addObject();
                target.put("group", t.groupId());
                target.put("exchange", t.exchange());
                target.put("metric", t.metric());
                target.put("shape", t.queryShape());
                if (t.instrument() != null) {
                    target.put("base", t.instrument().path("base").asText(null));
                }
                if (t.timeExpression() != null) {
                    target.put("time", t.timeExpression().toString());
                }
            }
            frameNode.put("has_pending_clarification",
                    frame.pendingClarification() != null && !frame.pendingClarification().isNull());
        }
        out.put("evidence_candidates", plannerInput == null ? 0 : plannerInput.evidence().size());
        out.put("text_history_count", plannerInput == null ? 0 : plannerInput.textHistoryCount());
        return out.toString();
    }

    /** CONTEXT_COMMITTED 的 output_json：轮次终态、本轮新保存的 factId 列表、活动 frame 是否更新。 */
    private static String contextCommittedSummary(String turnStatus, java.util.List<String> savedFactIds,
                                                  boolean frameUpdated) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var out = mapper.createObjectNode();
        out.put("status", turnStatus);
        var facts = out.putArray("saved_fact_ids");
        savedFactIds.forEach(facts::add);
        out.put("frame_updated", frameUpdated);
        return out.toString();
    }

    /** 按普通模型的每千 token 单价折算成本；单价为 0 时成本为 0，不影响主流程。 */
    private double computeCost(int promptTokens, int completionTokens) {
        return promptTokens / 1000.0 * llmConfig.getNormal().getPriceInputPer1k()
                + completionTokens / 1000.0 * llmConfig.getNormal().getPriceOutputPer1k();
    }

    private static ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("非法的 timezone（需为 IANA 时区，如 Asia/Shanghai）: " + timezone);
        }
    }
}
