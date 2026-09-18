package com.cexpilot.runtime;

/**
 * 一次 LLM 调用、Tool 调用或 DAG 规划的轨迹事件（审计回放需要记录的内容）。
 */
public record TraceEvent(String traceId,
                         String eventType,   // LLM_CALL / TOOL_CALL / PLAN
                         String name,
                         String inputJson,
                         String outputJson,
                         Long durationMs,
                         Integer promptTokens,
                         Integer completionTokens,
                         String error) {

    public static TraceEvent llmCall(String traceId, String inputJson, String outputJson,
                                     Long durationMs, Integer promptTokens, Integer completionTokens, String error) {
        return llmCall(traceId, "llm", inputJson, outputJson, durationMs, promptTokens, completionTokens, error);
    }

    public static TraceEvent llmCall(String traceId, String name, String inputJson, String outputJson,
                                     Long durationMs, Integer promptTokens, Integer completionTokens, String error) {
        return new TraceEvent(traceId, "LLM_CALL", name, inputJson, outputJson,
                durationMs, promptTokens, completionTokens, error);
    }

    public static TraceEvent toolCall(String traceId, String toolName, String inputJson,
                                      String outputJson, Long durationMs, String error) {
        return new TraceEvent(traceId, "TOOL_CALL", toolName, inputJson, outputJson,
                durationMs, null, null, error);
    }

    /** DAG 规划事件：每次生成的 Plan（含校验结果）落一条，error 为 null 表示校验通过。 */
    public static TraceEvent plan(String traceId, String outputJson, String error) {
        return new TraceEvent(traceId, "PLAN", "dag_planner", null, outputJson,
                null, null, null, error);
    }
}
