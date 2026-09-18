package com.cexpilot.intent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 未命中任何 intent 的 in-domain query 落库。
 * 积累后用于离线聚类，回答"用户真正想要但我们还没有的能力是什么"。
 */
@Repository
public class UnmatchedQueryRepository {

    private final JdbcTemplate jdbc;

    public UnmatchedQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String traceId, String conversationId, String question, String parseJson) {
        jdbc.update("""
                        INSERT INTO unmatched_query (trace_id, conversation_id, question, parse_json)
                        VALUES (?, ?, ?, CAST(? AS JSON))
                        """,
                traceId, conversationId, question, parseJson);
    }
}
