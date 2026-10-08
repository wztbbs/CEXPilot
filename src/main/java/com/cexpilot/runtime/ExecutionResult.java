package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/** 一次问答的执行结果。 */
public record ExecutionResult(String answer,
                              JsonNode evidence,
                              int toolCallCount,
                              int steps,
                              int promptTokens,
                              int completionTokens) {
}
