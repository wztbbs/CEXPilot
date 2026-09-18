package com.cexpilot.feedback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String traceId, String rating, String category, String comment) {
        jdbc.update(
                "INSERT INTO feedback (trace_id, rating, category, comment) VALUES (?, ?, ?, ?)",
                traceId, rating, category, comment);
    }
}
