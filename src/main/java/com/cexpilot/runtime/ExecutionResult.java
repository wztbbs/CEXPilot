package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次问答的执行结果。
 *
 * @param turnStatus      轮次终态提示；null = SUCCEEDED。NEEDS_CLARIFICATION 表示 Planner 要求澄清，
 *                        由编排层落轮次状态（不执行、不落 frame）
 * @param clarify         澄清问题原文（needsClarification 时有值），供编排层写入 frame 的 pendingClarification
 * @param reusedFactCount 本轮 REUSE_FACT 复用并成功播种的历史事实数（阶段二汇总计数）
 */
public record ExecutionResult(String answer,
                              JsonNode evidence,
                              int toolCallCount,
                              int steps,
                              int promptTokens,
                              int completionTokens,
                              String turnStatus,
                              String clarify,
                              int reusedFactCount) {

    /** 兼容旧构造：普通完成（SUCCEEDED），无澄清，无复用。 */
    public ExecutionResult(String answer, JsonNode evidence, int toolCallCount, int steps,
                           int promptTokens, int completionTokens) {
        this(answer, evidence, toolCallCount, steps, promptTokens, completionTokens, null, null, 0);
    }

    /** 兼容旧构造：带轮次终态/澄清，无复用计数。 */
    public ExecutionResult(String answer, JsonNode evidence, int toolCallCount, int steps,
                           int promptTokens, int completionTokens, String turnStatus, String clarify) {
        this(answer, evidence, toolCallCount, steps, promptTokens, completionTokens, turnStatus, clarify, 0);
    }
}
