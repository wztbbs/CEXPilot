package com.cexpilot.conversation;

import java.time.LocalDateTime;

public record QueryRecord(int queryNo, String question, String answer, String traceId, LocalDateTime createdAt,
                          String turnId, String turnStatus) {
    public QueryRecord(int queryNo, String question, String answer, String traceId, LocalDateTime createdAt) {
        this(queryNo, question, answer, traceId, createdAt, null, null);
    }
}
