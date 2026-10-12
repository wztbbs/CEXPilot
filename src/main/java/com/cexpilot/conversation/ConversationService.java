package com.cexpilot.conversation;

import com.cexpilot.config.ConversationConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Conversation Context：不做长期记忆，
 * 只把本对话最近几个 Query 注入 prompt，追问的指代消解交给 LLM 在这份上下文上完成。
 *
 * 轮次生命周期（多轮追问阶段一 a）：beginTurn 在请求开始即登记 RUNNING 轮次并占用会话执行权，
 * finishTurn 在结束（成功/失败）时提交状态并释放占用。DB 事务只覆盖登记与提交，不跨越 LLM 调用。
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
        }
        return conversationId;
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
        // acquireTurn 成功时已把 next_query_no +1，本轮拿到的是加之前的值
        long queryNo = state.nextQueryNo() - 1;
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
     */
    @Transactional
    public void finishTurn(String conversationId, TurnHandle turn, String status, String answer, String traceId,
                           String logicalPlanJson, String frameJson) {
        repository.finishTurn(turn.turnId(), status, answer, traceId, logicalPlanJson);
        if (frameJson != null) {
            repository.saveActiveFrame(conversationId, turn.turnId(), turn.fencingToken(), frameJson);
        }
        // 返回 false = 租约过期、占用已被新请求接管：正常路径，不覆盖新状态即可
        repository.releaseTurn(conversationId, turn.turnId(), turn.fencingToken());
        repository.touchConversation(conversationId, null);
    }

    /**
     * 澄清轮次（NEEDS_CLARIFICATION）：把澄清问题并入活动 frame 的 pendingClarification，
     * 保留 targets 等已知条件，用户补一个字段后能接上原任务。须在 finishTurn 释放占用前调用。
     */
    @Transactional
    public void recordPendingClarification(String conversationId, TurnHandle turn, String clarify) {
        com.fasterxml.jackson.databind.node.ObjectNode pending =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        pending.put("clarify", clarify);
        pending.put("turnId", turn.turnId());
        repository.updatePendingClarification(conversationId, turn.turnId(), turn.fencingToken(), pending.toString());
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
