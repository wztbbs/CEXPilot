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
     * @param parentTurnId 前端声明的上一轮 turnId，登记时落库；null 表示未携带
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
        try {
            // 4. 渲染对话上下文：Answer 侧仍用 renderContext 纯文本（口径不变）；
            //    Planner 侧用分层结构化上下文（活动 frame + 待补任务/失败尝试 + 带时间的近期问答）。
            String conversationContext = conversation.renderContext(resolvedConversationId);
            com.cexpilot.conversation.PlannerInput plannerInput =
                    contextService.buildPlannerInput(resolvedConversationId);

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
            double cost = computeCost(result.promptTokens(), result.completionTokens());

            traceRepository.finishTrace(traceId, "SUCCESS", result.answer(), result.steps(),
                    result.toolCallCount(), result.promptTokens(), result.completionTokens(),
                    cost, durationMs, null, result.reusedFactCount(),
                    result.plannerLlmCalls(), result.answerLlmCalls(), result.metricFetchCount(),
                    result.calculationCount(), result.httpRequestCount());
            traceFinished = true;

            // QueryFrame 随成功轮次提交覆盖活动 frame（失败/澄清轮次不覆盖）；下一轮由
            // ConversationContextService 读取注入 Planner 并做继承对账（阶段一 c）。
            String turnStatus = result.turnStatus() != null ? result.turnStatus() : "SUCCEEDED";
            com.cexpilot.metric.CompiledPlan compiledPlan = compiledPlanRef.get();
            String logicalPlanJson = compiledPlan != null ? compiledPlan.logicalPlanJson().toString() : null;
            String frameJson = compiledPlan != null && "SUCCEEDED".equals(turnStatus)
                    ? com.cexpilot.conversation.QueryFrame.fromCompiledPlan(resolvedConversationId,
                            turn.turnId(), question, requestContext.requestTime(), requestContext.userZone(),
                            compiledPlan, result.evidence()).toJson()
                    : null;
            // 澄清轮次：先独立保存待补任务（原问题 + 澄清问题，R1），再提交轮次
            if ("NEEDS_CLARIFICATION".equals(turnStatus) && result.clarify() != null) {
                conversation.recordPendingTask(resolvedConversationId, turn, question, result.clarify());
            }
            conversation.finishTurn(resolvedConversationId, turn, turnStatus, result.answer(), traceId,
                    logicalPlanJson, frameJson, null);
            turnFinished = true;
            // CONTEXT_COMMITTED（阶段三观测）：轮次状态提交后的最终状态摘要
            traceSink.record(new com.cexpilot.runtime.TraceEvent(traceId, "CONTEXT_COMMITTED", "ask_service",
                    null, contextCommittedSummary(turnStatus, result.savedFactIds(), frameJson != null),
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
            log.warn("问答失败 traceId={}: {}", traceId, e.getMessage());
            if (traceStarted && !traceFinished) {
                traceRepository.finishTrace(traceId, "FAILED", null, 0, 0,
                        null, null, null, durationMs, e.getMessage());
            }
            if (!turnFinished) {
                com.cexpilot.metric.CompiledPlan compiledPlan = compiledPlanRef.get();
                turnFinished = failTurnQuietly(resolvedConversationId, turn, question, traceId,
                        compiledPlan != null ? compiledPlan.logicalPlanJson().toString() : null,
                        traceStarted);
            }
            throw e;
        } finally {
            // 兜底释放执行占用（如 catch 里收尾也失败、或抛出的是 Error），避免会话被永久锁死
            if (!turnFinished) {
                com.cexpilot.metric.CompiledPlan compiledPlan = compiledPlanRef.get();
                failTurnQuietly(resolvedConversationId, turn, question, traceId,
                        compiledPlan != null ? compiledPlan.logicalPlanJson().toString() : null,
                        traceStarted);
            }
        }
    }

    /**
     * 失败收尾：轮次置 FAILED（带失败任务快照 lastAttempt，R2）并释放占用（frame 传 null，不覆盖活动 frame）；
     * 自身再失败只记日志，不掩盖原始异常。返回是否完成收尾。
     */
    private boolean failTurnQuietly(String conversationId, ConversationService.TurnHandle turn, String question,
                                    String traceId, String logicalPlanJson, boolean traceStarted) {
        try {
            String lastAttemptJson = lastAttemptSummary(turn.turnId(), question, traceId, logicalPlanJson);
            conversation.finishTurn(conversationId, turn, "FAILED", null, traceId, logicalPlanJson, null,
                    lastAttemptJson);
            if (traceStarted) {
                traceSink.record(new com.cexpilot.runtime.TraceEvent(traceId, "CONTEXT_COMMITTED", "ask_service",
                        null, contextCommittedSummary("FAILED", java.util.List.of(), false),
                        null, null, null, null, null, null));
            }
            return true;
        } catch (Exception ex) {
            log.warn("轮次失败收尾失败 turnId={}: {}", turn.turnId(), ex.getMessage());
            return false;
        }
    }

    /** 失败任务快照（R2）：下一轮"再试一次/那 OKX 呢"接续的依据；独立于成功 frame 保存。 */
    private static String lastAttemptSummary(String turnId, String question, String traceId, String logicalPlanJson) {
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        out.put("turnId", turnId);
        out.put("question", question);
        out.put("status", "FAILED");
        if (traceId != null) {
            out.put("traceId", traceId);
        }
        if (logicalPlanJson != null) {
            out.put("has_valid_plan", true);
        }
        return out.toString();
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
