package com.cexpilot.conversation;

import com.cexpilot.config.ConversationConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Conversation Context：不做长期记忆，
 * 按独立任务维护状态与版本，Planner 根据当前任务、历史候选和文字记录解析意图。
 *
 * 轮次生命周期（多轮追问阶段一 a）：beginTurn 在请求开始即登记 RUNNING 轮次并占用会话执行权，
 * finishTaskTurn 原子提交成功/澄清/失败任务、轮次快照及活动指针。事务不跨 LLM 调用。
 */
@Service
public class ConversationService {

    private static final int HISTORY_QUERY_LIMIT = 5;
    private static final int HISTORY_ANSWER_MAX_CHARS = 300;

    private final ConversationRepository repository;
    private final ConversationConfig config;

    public ConversationService(ConversationRepository repository, ConversationConfig config) {
        this.repository = repository;
        this.config = config;
    }

    /**
     * conversationId 为空时：当前约定 1 访客 1 会话，visitorId 已有会话则复用最近活跃的那个；
     * 否则新建。conversationId 不为空且不存在则按给定 id 创建。
     */
    public String getOrCreateConversation(String conversationId, String firstQuestion, String visitorId) {
        if (conversationId == null || conversationId.isBlank()) {
            String existing = visitorId == null ? null : repository.findLatestByVisitorId(visitorId);
            conversationId = existing != null ? existing : UUID.randomUUID().toString();
        }
        if (!repository.conversationExists(conversationId)) {
            repository.createConversation(conversationId, abbreviate(firstQuestion, 60), visitorId);
        } else if (visitorId != null) {
            requireOwned(conversationId, visitorId);
            repository.initializeTitle(conversationId, abbreviate(firstQuestion, 60));
        }
        return conversationId;
    }

    public void requireOwned(String id, String visitor) {
        if (!repository.ownedBy(id, visitor)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在");
    }

    public String createEmpty(String visitor) {
        String id = UUID.randomUUID().toString();
        repository.createConversation(id, null, visitor);
        return id;
    }

    public List<ConversationRepository.Summary> list(String visitor, String search, int limit, int offset) {
        return repository.list(visitor, search == null ? "" : search.trim(), Math.max(1, Math.min(100, limit)), Math.max(0, offset));
    }

    /** 会话的全部问答历史（页面回显用），无会话时返回空表。 */
    public List<QueryRecord> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return repository.allQueries(conversationId);
    }

    /** 访客最近活跃的会话 id；没有返回 null。 */
    public String latestConversationId(String visitorId) {
        return repository.findLatestByVisitorId(visitorId);
    }

    /** 渲染注入 system prompt 的最近对话上下文。 */
    public String renderContext(String conversationId) {
        List<QueryRecord> queries = repository.recentQueries(conversationId, HISTORY_QUERY_LIMIT);
        if (queries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (QueryRecord query : queries) {
            sb.append("用户：").append(query.question()).append('\n');
            if (query.answer() != null) {
                sb.append("助手：").append(abbreviate(query.answer(), HISTORY_ANSWER_MAX_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 登记成功后的轮次句柄：序号、轮次 id 与 fencing token（提交时校验）。 */
    public record TurnHandle(long queryNo, String turnId, long fencingToken) {
    }

    /**
     * 请求开始时登记轮次（短事务）：原子分配 query_no + turn_id、置 running_turn_id、
     * 写一行 turn_status=RUNNING 的 conversation_query（question 先写，answer 提交时后补）。
     *
     * @param requestTime 本请求的固定时间基准（RequestContext），写入 started_at；用应用时钟而非
     *                    DB 的 NOW()——MySQL 服务器时区与 JDBC 会话时区不一致时，DB 时钟写出的
     *                    DATETIME 读回会被平移，注入 prompt 的历史时间随之错位
     * @return 登记成功的句柄；会话已有未过期的执行中轮次时返回 null（同会话串行约束）
     */
    @Transactional
    public TurnHandle beginTurn(String conversationId, String question, String parentTurnId,
                                java.time.Instant requestTime) {
        String turnId = UUID.randomUUID().toString();
        ConversationRepository.ConversationStateRow state =
                repository.acquireTurn(conversationId, turnId, config.getTurnLease().toMillis());
        if (!turnId.equals(state.runningTurnId())) {
            return null;
        }
        // acquireTurn 持有 state 行锁直到此事务结束，校验与登记之间不会有别的任务提交。
        // null 兼容旧客户端；空串明确表示页面还没有看到任何完成轮次。
        if (parentTurnId != null) {
            String latest = repository.latestCompletedTurnId(conversationId);
            if (!parentTurnId.equals(latest == null ? "" : latest)) {
                // RuntimeException 回滚 acquireTurn：冲突不分配序号、不占用会话、不调用 LLM。
                throw new StaleContextException(conversationId, latest);
            }
        }
        // acquireTurn 成功时已推进 last_query_no，返回值就是本轮分配的序号
        long queryNo = state.lastQueryNo();
        repository.insertRunningTurn(conversationId, queryNo, turnId, parentTurnId, question, requestTime);
        return new TurnHandle(queryNo, turnId, state.fencingToken());
    }

    /**
     * 结束时提交轮次（短事务）：置终态（SUCCEEDED/FAILED 等）、补 answer/trace_id/logical_plan_json/finished_at、
     * 清 running_turn_id、version+1。fencing token 不匹配（租约过期被接管）时不覆盖会话状态，
     * 轮次行仍按事实落终态。
     *
     * @param logicalPlanJson 校验通过的 LogicalPlan 原文 JSON，规划未通过校验时为 null
     * @param frameJson       本轮产生的活动 QueryFrame JSON；仅成功轮次传入，失败轮次传 null（不覆盖活动 frame）
     * @param lastAttemptJson 失败轮次传入的任务快照 JSON（R2），成功轮次传 null；SUCCEEDED 同时清空
     *                        待补澄清任务与历史失败尝试（任务已了结，NEEDS_CLARIFICATION 不清）
     */
    @Transactional
    public boolean finishTurn(String conversationId, TurnHandle turn, String status, String answer, String traceId,
                              String logicalPlanJson, String frameJson, String lastAttemptJson) {
        repository.finishTurn(turn.turnId(), status, answer, traceId, logicalPlanJson, frameJson);
        boolean frameUpdated = false;
        if (frameJson != null) {
            // 4.3：用真实写入结果（fencing 不匹配被拒时为 false），CONTEXT_COMMITTED 不再说谎
            frameUpdated = repository.saveActiveFrame(conversationId, turn.turnId(), turn.fencingToken(), frameJson);
        }
        if ("SUCCEEDED".equals(status)) {
            repository.clearTaskState(conversationId, turn.turnId(), turn.fencingToken());
        } else if ("FAILED".equals(status) && lastAttemptJson != null) {
            repository.saveLastAttempt(conversationId, turn.turnId(), turn.fencingToken(), lastAttemptJson);
        }
        // 返回 false = 租约过期、占用已被新请求接管：正常路径，不覆盖新状态即可
        repository.releaseTurn(conversationId, turn.turnId(), turn.fencingToken());
        repository.touchConversation(conversationId, null);
        return frameUpdated;
    }

    public record TaskCommit(boolean updated, String frameId, long version, TaskState state) {}

    public com.fasterxml.jackson.databind.JsonNode routingState(String conversationId) { return repository.routingState(conversationId); }

    /** Query 继续既有任务生命周期；Monitor/Router 澄清不写入查询任务。 */
    @Transactional
    public TaskCommit finishRoutedTurn(String conversationId,TurnHandle turn,String question,String status,String answer,
            String traceId,com.cexpilot.metric.CompiledPlan plan,com.fasterxml.jackson.databind.JsonNode evidence,TaskScope scope,
            java.time.Instant requestTime,java.time.ZoneId zone,String taskResponse,String agent,
            com.fasterxml.jackson.databind.JsonNode payload) {
        boolean owns=repository.ownsTurnForUpdate(conversationId,turn.turnId(),turn.fencingToken());
        var commit=finishTaskTurn(conversationId,turn,question,status,answer,traceId,
                "query".equals(agent)?plan:null,evidence,"query".equals(agent)?scope:null,requestTime,zone,taskResponse);
        repository.saveAgentOutcome(turn.turnId(),agent,payload);
        if(owns) {
            com.fasterxml.jackson.databind.JsonNode monitor=null;
            if("monitor".equals(agent)) {
                var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
                var previous=repository.routingState(conversationId).path("monitor_context");
                var state=previous.isObject()?((com.fasterxml.jackson.databind.node.ObjectNode)previous).deepCopy():mapper.createObjectNode();
                state.put("last_question",abbreviate(question,1000)).put("last_reply",abbreviate(answer,1000))
                        .put("status",payload==null?status:payload.path("status").asText(status));
                if(payload!=null && payload.has("candidate")) state.set("candidate",payload.path("candidate"));
                if(payload!=null && payload.has("confirmation")) state.set("confirmation",payload.path("confirmation"));
                var dialogue=mapper.createArrayNode(); var old=state.path("dialogue");
                for(int i=Math.max(0,old.size()-6);i<old.size();i++) dialogue.add(old.get(i));
                dialogue.addObject().put("question",abbreviate(question,1000)).put("reply",abbreviate(answer,1000));
                state.set("dialogue",dialogue); monitor=state;
            }
            repository.saveRoutingState(conversationId,turn.fencingToken(),agent,monitor);
        }
        return commit;
    }

    /** 无 Dispatcher 的兼容入口也在同一事务内保存表单与轮次，刷新后可恢复。 */
    @Transactional
    public TaskCommit finishQueryTurn(String conversationId, TurnHandle turn, String question, String status,
            String answer, String traceId, com.cexpilot.metric.CompiledPlan plan,
            com.fasterxml.jackson.databind.JsonNode evidence, TaskScope scope, java.time.Instant requestTime,
            java.time.ZoneId zone, String taskResponse, com.fasterxml.jackson.databind.JsonNode payload) {
        var commit = finishTaskTurn(conversationId, turn, question, status, answer, traceId, plan,
                evidence, scope, requestTime, zone, taskResponse);
        repository.saveAgentOutcome(turn.turnId(), "query", payload);
        return commit;
    }

    /** 任务状态、轮次快照、活动指针和占用释放原子提交；来源版本与新增版本分开。 */
    @Transactional
    public TaskCommit finishTaskTurn(String conversationId, TurnHandle turn, String question, String status,
            String answer, String traceId, com.cexpilot.metric.CompiledPlan plan,
            com.fasterxml.jackson.databind.JsonNode evidence, TaskScope scope, java.time.Instant requestTime,
            java.time.ZoneId zone, String taskResponse) {
        String logicalJson = plan == null ? null : plan.logicalPlanJson().toString();
        if (!repository.ownsTurnForUpdate(conversationId, turn.turnId(), turn.fencingToken())) {
            repository.finishTurn(turn.turnId(), status, answer, traceId, logicalJson, null);
            return new TaskCommit(false, null, 0, null);
        }
        if (scope == null) {
            // 出域/无任务决策的普通说明不改变已有任务。
            repository.finishTurn(turn.turnId(), status, answer, traceId, logicalJson, null);
            repository.releaseTurn(conversationId, turn.turnId(), turn.fencingToken());
            repository.touchConversation(conversationId, null);
            return new TaskCommit(false, null, 0, null);
        }
        TaskState base = scope.action() == TaskScope.Action.NEW ? null : scope.base();
        if (base != null && !conversationId.equals(base.conversationId())) {
            throw new IllegalArgumentException("任务不属于当前会话");
        }
        String frameId = base == null ? UUID.randomUUID().toString() : base.frameId();
        TaskState latest = repository.findTask(conversationId, frameId);
        long version = latest == null ? (base == null ? 1 : base.version() + 1) : latest.version() + 1;
        var selectedContext = scope.executionContext(new com.cexpilot.runtime.RequestContext(zone, requestTime));
        TaskState next = TaskState.complete(frameId, conversationId, version, base, turn, question, status,
                taskResponse == null && plan == null ? answer : taskResponse, plan, evidence,
                selectedContext.requestTime(), selectedContext.userZone());
        // 来源版本可以很旧，但失败/澄清不能把该任务最近成功状态倒退到那个旧版本。
        if (latest != null && (!"SUCCEEDED".equals(status) || plan == null)) {
            next = next.withSuccessfulFrame(latest.successfulFrame());
        }
        repository.saveTask(next);
        repository.finishTaskQuery(turn.turnId(), status, answer, traceId, logicalJson, next, scope);
        repository.activateTask(conversationId, next);
        repository.releaseTurn(conversationId, turn.turnId(), turn.fencingToken());
        repository.touchConversation(conversationId, null);
        return new TaskCommit(true, frameId, version, next);
    }

    /** 兼容旧调用（无失败任务快照）。 */
    @Transactional
    public void finishTurn(String conversationId, TurnHandle turn, String status, String answer, String traceId,
                           String logicalPlanJson, String frameJson) {
        finishTurn(conversationId, turn, status, answer, traceId, logicalPlanJson, frameJson, null);
    }

    /**
     * 澄清轮次（NEEDS_CLARIFICATION）：独立保存待补任务（原问题 + 澄清问题，R1）——
     * 不依赖活动 frame 存在，新会话首轮澄清也能接续；澄清不挂在旧任务的 frame 上。
     * 须在 finishTurn 释放占用前调用。
     */
    @Transactional
    public void recordPendingTask(String conversationId, TurnHandle turn, String question, String clarify) {
        com.fasterxml.jackson.databind.node.ObjectNode pending =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        pending.put("question", question);
        pending.put("clarify", clarify);
        pending.put("turnId", turn.turnId());
        repository.savePendingTask(conversationId, turn.turnId(), turn.fencingToken(), pending.toString());
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
