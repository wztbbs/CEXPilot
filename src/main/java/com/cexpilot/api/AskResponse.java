package com.cexpilot.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @param turnId 本轮问答的轮次 id；忙碌响应（未登记轮次）为 null
 * @param busy   true 表示同会话已有执行中请求、本次未执行（忙碌），前端据此区分正常答案与忙碌提示
 * <p>
 * plannerLlmCalls / answerLlmCalls / metricFetchCount / calculationCount / httpRequestCount /
 * reusedFactCount 为阶段三拆分计数（口径见 ExecutionResult）；忙碌响应均为 0。
 */
public record AskResponse(String conversationId,
                          String traceId,
                          String answer,
                          JsonNode evidence,
                          int toolCalls,
                          int llmSteps,
                          long durationMs,
                          String turnId,
                          boolean busy,
                          int plannerLlmCalls,
                          int answerLlmCalls,
                          int metricFetchCount,
                          int calculationCount,
                          int httpRequestCount,
                          int reusedFactCount) {

    /** 正常完成路径的兼容构造（非忙碌，无拆分计数）。 */
    public AskResponse(String conversationId, String traceId, String answer, JsonNode evidence,
                       int toolCalls, int llmSteps, long durationMs, String turnId) {
        this(conversationId, traceId, answer, evidence, toolCalls, llmSteps, durationMs, turnId, false);
    }

    /** 兼容构造：无拆分计数。 */
    public AskResponse(String conversationId, String traceId, String answer, JsonNode evidence,
                       int toolCalls, int llmSteps, long durationMs, String turnId, boolean busy) {
        this(conversationId, traceId, answer, evidence, toolCalls, llmSteps, durationMs, turnId, busy,
                0, 0, 0, 0, 0, 0);
    }
}
