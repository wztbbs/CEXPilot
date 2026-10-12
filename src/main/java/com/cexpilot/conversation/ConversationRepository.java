package com.cexpilot.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean conversationExists(String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count != null && count > 0;
    }

    public void createConversation(String conversationId, String title, String visitorId) {
        jdbc.update(
                "INSERT INTO conversation (conversation_id, title, visitor_id) VALUES (?, ?, ?)",
                conversationId, title, visitorId);
    }

    /** 访客最近活跃的会话；没有返回 null。 */
    public String findLatestByVisitorId(String visitorId) {
        List<String> ids = jdbc.query(
                "SELECT conversation_id FROM conversation WHERE visitor_id = ? ORDER BY last_active_at DESC LIMIT 1",
                (rs, rowNum) -> rs.getString("conversation_id"), visitorId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 会话的全部问答，时间正序（历史回显用，不限条数）。 */
    public List<QueryRecord> allQueries(String conversationId) {
        return jdbc.query("""
                        SELECT query_no, question, answer, trace_id, created_at
                        FROM conversation_query
                        WHERE conversation_id = ?
                        ORDER BY query_no ASC
                        """,
                (rs, rowNum) -> new QueryRecord(
                        rs.getInt("query_no"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                conversationId);
    }

    public void touchConversation(String conversationId, String title) {
        jdbc.update(
                "UPDATE conversation SET last_active_at = NOW(), title = COALESCE(title, ?) WHERE conversation_id = ?",
                title, conversationId);
    }

    /** 会话状态行（conversation_state）。nextQueryNo 为"下一个可用序号"。 */
    public record ConversationStateRow(long nextQueryNo, String runningTurnId, long fencingToken) {
    }

    /**
     * 原子地尝试获取会话执行占用并推进序号：
     * 无占用或租约已过期时占用成功（running_turn_id 置为本轮 turnId、next_query_no+1、fencing_token+1）；
     * 有未过期占用时行不变。调用方比对返回行的 runningTurnId 与本次 turnId 判断是否拿到占用。
     * 单行 upsert，靠 InnoDB 行锁保证并发下只有一个请求占用成功。
     */
    public ConversationStateRow acquireTurn(String conversationId, String turnId, long leaseMillis) {
        jdbc.update("""
                        INSERT INTO conversation_state (conversation_id, version, next_query_no, running_turn_id, lease_until, fencing_token, updated_at)
                        VALUES (?, 0, 2, ?, DATE_ADD(NOW(6), INTERVAL ? MICROSECOND), 1, NOW(6))
                        ON DUPLICATE KEY UPDATE
                            next_query_no   = IF(running_turn_id IS NULL OR lease_until <= NOW(6), next_query_no + 1, next_query_no),
                            fencing_token   = IF(running_turn_id IS NULL OR lease_until <= NOW(6), fencing_token + 1, fencing_token),
                            running_turn_id = IF(running_turn_id IS NULL OR lease_until <= NOW(6), VALUES(running_turn_id), running_turn_id),
                            lease_until     = IF(running_turn_id IS NULL OR lease_until <= NOW(6), VALUES(lease_until), lease_until),
                            updated_at      = NOW(6)
                        """,
                conversationId, turnId, leaseMillis * 1000);
        return jdbc.queryForObject("""
                        SELECT next_query_no, running_turn_id, fencing_token
                        FROM conversation_state WHERE conversation_id = ?
                        """,
                (rs, rowNum) -> new ConversationStateRow(
                        rs.getLong("next_query_no"), rs.getString("running_turn_id"), rs.getLong("fencing_token")),
                conversationId);
    }

    /** 登记 RUNNING 轮次：question 先写，answer 与 trace_id 在提交时后补。
     *  started_at 由应用时钟（requestTime）写入，不用 NOW()：应用写入的 Timestamp 两个方向
     *  都走 JDBC 会话时区转换、往返自洽；DB 时钟写出的值在服务器时区与会话时区不一致时会错位。 */
    public void insertRunningTurn(String conversationId, long queryNo, String turnId, String parentTurnId,
                                  String question, java.time.Instant requestTime) {
        jdbc.update("""
                        INSERT INTO conversation_query (conversation_id, query_no, question, turn_id, parent_turn_id, turn_status, started_at)
                        VALUES (?, ?, ?, ?, ?, 'RUNNING', ?)
                        """,
                conversationId, queryNo, question, turnId, parentTurnId, java.sql.Timestamp.from(requestTime));
    }

    /** 提交轮次结果：状态、答案、trace、校验通过的 LogicalPlan 原文（无则 null）、结束时间。 */
    public void finishTurn(String turnId, String status, String answer, String traceId, String logicalPlanJson) {
        jdbc.update("""
                        UPDATE conversation_query
                        SET turn_status = ?, answer = ?, trace_id = ?, logical_plan_json = ?, finished_at = NOW(6)
                        WHERE turn_id = ?
                        """,
                status, answer, traceId, logicalPlanJson, turnId);
    }

    /**
     * 提交活动 QueryFrame（仅成功轮次调用）。带 running_turn_id + fencing_token 条件：
     * 租约过期被接管后，旧执行者不得覆盖新 frame。返回是否写入。
     */
    public boolean saveActiveFrame(String conversationId, String turnId, long fencingToken, String frameJson) {
        return jdbc.update("""
                        UPDATE conversation_state
                        SET active_frame_json = ?
                        WHERE conversation_id = ? AND running_turn_id = ? AND fencing_token = ?
                        """,
                frameJson, conversationId, turnId, fencingToken) > 0;
    }

    /** 活动 QueryFrame 原文；无状态行或未提交过 frame 时返回 null。 */
    public String findActiveFrameJson(String conversationId) {
        List<String> jsons = jdbc.query(
                "SELECT active_frame_json FROM conversation_state WHERE conversation_id = ?",
                (rs, rowNum) -> rs.getString("active_frame_json"), conversationId);
        return jsons.isEmpty() ? null : jsons.get(0);
    }

    /**
     * 澄清轮次：把待补问题合并进活动 frame 的 pendingClarification（保留 targets 等其他字段），
     * 让用户补一个字段后能接上原任务。带 fencing 条件；无活动 frame 时 no-op。
     */
    public boolean updatePendingClarification(String conversationId, String turnId, long fencingToken, String pendingJson) {
        return jdbc.update("""
                        UPDATE conversation_state
                        SET active_frame_json = JSON_SET(active_frame_json, '$.pendingClarification', CAST(? AS JSON))
                        WHERE conversation_id = ? AND running_turn_id = ? AND fencing_token = ?
                          AND active_frame_json IS NOT NULL
                        """,
                pendingJson, conversationId, turnId, fencingToken) > 0;
    }

    /**
     * 释放执行占用并推进版本。fencing token 校验：不匹配说明租约已过期被新请求接管，
     * 旧执行者不得覆盖新状态，返回 false。
     */
    public boolean releaseTurn(String conversationId, String turnId, long fencingToken) {
        return jdbc.update("""
                        UPDATE conversation_state
                        SET running_turn_id = NULL, lease_until = NULL, version = version + 1, updated_at = NOW(6)
                        WHERE conversation_id = ? AND running_turn_id = ? AND fencing_token = ?
                        """,
                conversationId, turnId, fencingToken) > 0;
    }

    public List<QueryRecord> recentQueries(String conversationId, int limit) {
        // 只把已成功（或轮次功能上线前的历史）轮次注入上下文：
        // RUNNING 行（含当前这轮）与 FAILED 行不进 prompt，保持与"成功后追加聊天记录"的旧口径一致。
        // 发生时间优先取应用时钟写入的 started_at；轮次功能上线前的旧行回退 created_at（DB 时钟，可能带时区偏移）。
        List<QueryRecord> desc = jdbc.query("""
                        SELECT query_no, question, answer, trace_id, COALESCE(started_at, created_at) AS occurred_at
                        FROM conversation_query
                        WHERE conversation_id = ?
                          AND (turn_status IS NULL OR turn_status = 'SUCCEEDED')
                        ORDER BY query_no DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new QueryRecord(
                        rs.getInt("query_no"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("occurred_at").toLocalDateTime()),
                conversationId, limit);
        // 翻转为时间正序，让最早的在前
        java.util.Collections.reverse(desc);
        return desc;
    }
}
