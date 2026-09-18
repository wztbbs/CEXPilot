package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次问答的执行结果。intent 是 planner 归类的统计 hint（命中的 intent 名或 UNKNOWN）；
 * 出域回答没有经过归类，为 null。
 */
public record ExecutionResult(String answer,
                              JsonNode evidence,
                              int toolCallCount,
                              int steps,
                              int promptTokens,
                              int completionTokens,
                              String intent) {
}
