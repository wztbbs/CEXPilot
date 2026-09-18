package com.cexpilot.conversation;

import java.time.LocalDateTime;

public record QueryRecord(int queryNo, String question, String answer, String traceId, LocalDateTime createdAt) {
}
