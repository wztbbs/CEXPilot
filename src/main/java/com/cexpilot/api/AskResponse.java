package com.cexpilot.api;

import com.fasterxml.jackson.databind.JsonNode;

public record AskResponse(String conversationId,
                          String traceId,
                          String answer,
                          JsonNode evidence,
                          int toolCalls,
                          int llmSteps,
                          long durationMs) {
}
