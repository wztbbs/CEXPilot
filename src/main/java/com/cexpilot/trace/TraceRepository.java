package com.cexpilot.trace;

import com.cexpilot.runtime.TraceEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TraceRepository {

    private final JdbcTemplate jdbc;

    public TraceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void startTrace(String traceId, String conversationId, String question, String model,
                           String promptVersion, String visitorId) {
        jdbc.update("""
                        INSERT INTO ask_trace (trace_id, conversation_id, question, status, model, prompt_version, visitor_id)
                        VALUES (?, ?, ?, 'RUNNING', ?, ?, ?)
                        """,
                traceId, conversationId, question, model, promptVersion, visitorId);
    }

    public void finishTrace(String traceId, String status, String answer, int llmSteps, int toolCalls,
                            Integer promptTokens, Integer completionTokens, Double cost,
                            long durationMs, String error, String intent) {
        jdbc.update("""
                        UPDATE ask_trace
                        SET status = ?, answer = ?, llm_steps = ?, tool_calls = ?,
                            prompt_tokens = ?, completion_tokens = ?, cost = ?, duration_ms = ?,
                            error = ?, intent = ?, finished_at = NOW()
                        WHERE trace_id = ?
                        """,
                status, answer, llmSteps, toolCalls, promptTokens, completionTokens, cost,
                durationMs, error, intent, traceId);
    }

    public void appendEvent(String traceId, TraceEvent event) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM trace_event WHERE trace_id = ?",
                Integer.class, traceId);
        jdbc.update("""
                        INSERT INTO trace_event
                            (trace_id, seq, event_type, name, input_json, output_json,
                             duration_ms, prompt_tokens, completion_tokens, error)
                        VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?)
                        """,
                traceId, seq, event.eventType(), event.name(),
                event.inputJson(), event.outputJson(),
                event.durationMs(), event.promptTokens(), event.completionTokens(), event.error());
    }

    public Map<String, Object> findTrace(String traceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ask_trace WHERE trace_id = ?", traceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findEvents(String traceId) {
        return jdbc.queryForList(
                "SELECT * FROM trace_event WHERE trace_id = ? ORDER BY seq", traceId);
    }

    // 窗口由数据库自己的 NOW() 计算：created_at 是 DB 时钟写的，比较双方必须同为 DB 时钟，
    // 否则应用服务器（如硅谷）与数据库（如 UTC+8）时区不一致时会捞空。
    public List<Map<String, Object>> findTracesBetween(long beginMinutesAgo, long endMinutesAgo) {
        return jdbc.queryForList("""
                        SELECT * FROM ask_trace
                        WHERE created_at BETWEEN NOW() - INTERVAL ? MINUTE AND NOW() - INTERVAL ? MINUTE
                        ORDER BY created_at DESC
                        """,
                beginMinutesAgo, endMinutesAgo);
    }

    public Timestamp dbNow() {
        return jdbc.queryForObject("SELECT NOW()", Timestamp.class);
    }

    public Map<String, List<Map<String, Object>>> findEventsByTraceIds(List<String> traceIds) {
        return groupByTraceId(
                "SELECT * FROM trace_event WHERE trace_id IN (%s) ORDER BY trace_id, seq", traceIds);
    }

    public Map<String, List<Map<String, Object>>> findFeedbackByTraceIds(List<String> traceIds) {
        return groupByTraceId(
                "SELECT * FROM feedback WHERE trace_id IN (%s) ORDER BY trace_id, id", traceIds);
    }

    private Map<String, List<Map<String, Object>>> groupByTraceId(String sql, List<String> traceIds) {
        if (traceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(traceIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(String.format(sql, placeholders), traceIds.toArray());
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent((String) row.get("trace_id"), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }
}
