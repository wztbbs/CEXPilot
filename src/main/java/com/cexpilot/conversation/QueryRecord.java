package com.cexpilot.conversation;

import java.time.LocalDateTime;

public record QueryRecord(int queryNo, String question, String answer, String traceId, LocalDateTime createdAt,
                          String turnId, String turnStatus, String agentType, com.fasterxml.jackson.databind.JsonNode planning) {
    public QueryRecord(int queryNo,String question,String answer,String traceId,LocalDateTime createdAt,String turnId,String turnStatus) {
        this(queryNo,question,answer,traceId,createdAt,turnId,turnStatus,null,null);
    }
    public QueryRecord(int queryNo, String question, String answer, String traceId, LocalDateTime createdAt) {
        this(queryNo, question, answer, traceId, createdAt, null, null);
    }
}
