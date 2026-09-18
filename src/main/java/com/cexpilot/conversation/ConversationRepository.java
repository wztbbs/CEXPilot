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

    public int nextQueryNo(String conversationId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(query_no), 0) FROM conversation_query WHERE conversation_id = ?",
                Integer.class, conversationId);
        return (max == null ? 0 : max) + 1;
    }

    public void recordQuery(String conversationId, int queryNo, String question, String answer, String traceId) {
        jdbc.update("""
                        INSERT INTO conversation_query (conversation_id, query_no, question, answer, trace_id)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                conversationId, queryNo, question, answer, traceId);
    }

    public List<QueryRecord> recentQueries(String conversationId, int limit) {
        List<QueryRecord> desc = jdbc.query("""
                        SELECT query_no, question, answer, trace_id, created_at
                        FROM conversation_query
                        WHERE conversation_id = ?
                        ORDER BY query_no DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new QueryRecord(
                        rs.getInt("query_no"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                conversationId, limit);
        // 翻转为时间正序，让最早的在前
        java.util.Collections.reverse(desc);
        return desc;
    }
}
