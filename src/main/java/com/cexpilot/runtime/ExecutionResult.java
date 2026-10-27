package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次问答的执行结果。
 *
 * @param turnStatus       轮次终态提示；null = SUCCEEDED。NEEDS_CLARIFICATION 表示 Planner 要求澄清，
 *                         由编排层保存所选任务的澄清版本（不执行 DAG）
 * @param clarify          澄清问题原文（needsClarification 时有值），供编排层追加任务的未完成问答链
 * @param reusedFactCount  本轮 REUSE_FACT 复用并成功播种的历史事实数（阶段二汇总计数）
 * @param plannerLlmCalls  planner 阶段 LLM 调用次数（含修复重试；阶段三计数拆分）
 * @param answerLlmCalls   answer 阶段 LLM 调用次数（0 或 1）
 * @param metricFetchCount metric 节点真实取数次数（REUSE_FACT 节点不算）
 * @param calculationCount 算子节点执行次数
 * @param httpRequestCount 本轮真实发出的交易所 HTTP 请求数（一个节点分页/重试会记多次）
 * @param savedFactIds     本轮新保存的事实 factId 列表（不含 REUSE_FACT 沿用的历史 factId）
 * @param llmUsages        每次调用实际模型及其单价估算；null 仅兼容旧构造，未知 usage 的费用为 null
 */
public record ExecutionResult(String answer,
                              JsonNode evidence,
                              int toolCallCount,
                              int steps,
                              int promptTokens,
                              int completionTokens,
                              String turnStatus,
                              String clarify,
                              int reusedFactCount,
                              int plannerLlmCalls,
                              int answerLlmCalls,
                              int metricFetchCount,
                              int calculationCount,
                              int httpRequestCount,
                              List<String> savedFactIds, com.cexpilot.conversation.TaskScope taskScope,
                              List<com.cexpilot.llm.LlmUsage> llmUsages,
                              int routerLlmCalls, String agentType, JsonNode agentPayload) {

    public ExecutionResult(String answer,JsonNode evidence,int toolCallCount,int steps,int promptTokens,int completionTokens,
            String turnStatus,String clarify,int reusedFactCount,int plannerLlmCalls,int answerLlmCalls,int metricFetchCount,
            int calculationCount,int httpRequestCount,List<String> savedFactIds,com.cexpilot.conversation.TaskScope scope,
            List<com.cexpilot.llm.LlmUsage> usages) {
        this(answer,evidence,toolCallCount,steps,promptTokens,completionTokens,turnStatus,clarify,reusedFactCount,
                plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,savedFactIds,scope,usages,0,"query",null);
    }

    public ExecutionResult(String answer, JsonNode evidence, int toolCallCount, int steps, int promptTokens,
            int completionTokens, String turnStatus, String clarify, int reusedFactCount, int plannerLlmCalls,
            int answerLlmCalls, int metricFetchCount, int calculationCount, int httpRequestCount,
            List<String> savedFactIds, com.cexpilot.conversation.TaskScope taskScope) {
        this(answer, evidence, toolCallCount, steps, promptTokens, completionTokens, turnStatus, clarify,
                reusedFactCount, plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount,
                httpRequestCount, savedFactIds, taskScope, null);
    }

    public ExecutionResult(String answer, JsonNode evidence, int toolCallCount, int steps, int promptTokens,
            int completionTokens, String turnStatus, String clarify, int reusedFactCount, int plannerLlmCalls,
            int answerLlmCalls, int metricFetchCount, int calculationCount, int httpRequestCount,
            List<String> savedFactIds) {
        this(answer, evidence, toolCallCount, steps, promptTokens, completionTokens, turnStatus, clarify,
                reusedFactCount, plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount,
                httpRequestCount, savedFactIds, null);
    }

    public ExecutionResult withTaskScope(com.cexpilot.conversation.TaskScope scope) {
        return new ExecutionResult(answer, evidence, toolCallCount, steps, promptTokens, completionTokens,
                turnStatus, clarify, reusedFactCount, plannerLlmCalls, answerLlmCalls, metricFetchCount,
                calculationCount, httpRequestCount, savedFactIds, scope, llmUsages,routerLlmCalls,agentType,agentPayload);
    }

    public ExecutionResult withLlmUsages(List<com.cexpilot.llm.LlmUsage> usages) {
        return new ExecutionResult(answer, evidence, toolCallCount, steps, promptTokens, completionTokens,
                turnStatus, clarify, reusedFactCount, plannerLlmCalls, answerLlmCalls, metricFetchCount,
                calculationCount, httpRequestCount, savedFactIds, taskScope, usages,routerLlmCalls,agentType,agentPayload);
    }

    public ExecutionResult withAgent(String type,JsonNode payload) {
        return new ExecutionResult(answer,evidence,toolCallCount,steps,promptTokens,completionTokens,turnStatus,clarify,
                reusedFactCount,plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,
                savedFactIds,taskScope,llmUsages,routerLlmCalls,type,payload);
    }

    public ExecutionResult withRouterUsages(List<com.cexpilot.llm.LlmUsage> routing) {
        var combined=new java.util.ArrayList<>(routing);
        if(llmUsages!=null) combined.addAll(llmUsages);
        else if(plannerLlmCalls+answerLlmCalls>0) combined.add(new com.cexpilot.llm.LlmUsage("unknown",null,null,null,null));
        return new ExecutionResult(answer,evidence,toolCallCount,steps,
                promptTokens+com.cexpilot.llm.LlmUsage.knownPromptTokens(routing),
                completionTokens+com.cexpilot.llm.LlmUsage.knownCompletionTokens(routing),turnStatus,clarify,reusedFactCount,
                plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,savedFactIds,taskScope,
                combined,routing.size(),agentType,agentPayload);
    }

    public ExecutionResult {
        savedFactIds = savedFactIds == null ? List.of() : List.copyOf(savedFactIds);
        llmUsages = llmUsages == null ? null : List.copyOf(llmUsages);
    }

    /** 兼容旧构造：普通完成（SUCCEEDED），无澄清，无复用，无拆分计数。 */
    public ExecutionResult(String answer, JsonNode evidence, int toolCallCount, int steps,
                           int promptTokens, int completionTokens) {
        this(answer, evidence, toolCallCount, steps, promptTokens, completionTokens, null, null, 0);
    }

    /** 兼容旧构造：带轮次终态/澄清，无复用计数。 */
    public ExecutionResult(String answer, JsonNode evidence, int toolCallCount, int steps,
                           int promptTokens, int completionTokens, String turnStatus, String clarify) {
        this(answer, evidence, toolCallCount, steps, promptTokens, completionTokens, turnStatus, clarify, 0);
    }

    /** 兼容旧构造：带复用计数，无阶段三拆分计数。 */
    public ExecutionResult(String answer, JsonNode evidence, int toolCallCount, int steps,
                           int promptTokens, int completionTokens, String turnStatus, String clarify,
                           int reusedFactCount) {
        this(answer, evidence, toolCallCount, steps, promptTokens, completionTokens, turnStatus, clarify,
                reusedFactCount, 0, 0, 0, 0, 0, List.of());
    }
}
