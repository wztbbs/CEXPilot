package com.cexpilot.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 从完整事件生成评测摘要；不修改原事件，不把旧的编译成功或正常说明推断成最终可执行计划。 */
public record PlannerTraceView(List<Map<String, Object>> attempts, JsonNode finalPlan, JsonNode finalCompiledPlan) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PlannerTraceView from(List<Map<String, Object>> events) {
        var rows = new ArrayList<>(events);
        rows.sort(Comparator.comparingLong(e -> number(e.get("seq"), 0)));
        var entries = new TreeMap<Long, Entry>();
        Entry current = null;
        long ordinal = 0;
        for (var row : rows) {
            String type = Objects.toString(row.get("event_type"), "");
            if ("LLM_CALL".equals(type) && "dag_planner".equals(row.get("name"))) {
                JsonNode input = json(row.get("input_json")), output = json(row.get("output_json"));
                long attempt = input.path("attempt").canConvertToLong() && input.path("attempt").asLong() > 0
                        ? input.path("attempt").asLong() : ordinal + 1;
                ordinal = Math.max(ordinal, attempt);
                current = entries.computeIfAbsent(attempt, Entry::new);
                current.values.put("llm_call_seq", row.get("seq"));
                current.metadata(input, output);
                boolean failed = nonEmpty(row.get("error"));
                current.values.put("call_status", failed ? "FAILED" : output.isNull() ? "UNKNOWN" : "SUCCEEDED");
                current.values.put("call_error", failed ? row.get("error") : null);
                if (failed) current.values.put("result", "CALL_FAILED");
                for (String key : List.of("duration_ms", "prompt_tokens", "completion_tokens", "ttft_ms", "cached_tokens")) {
                    current.values.put(key, row.get(key));
                }
                current.values.put("estimated_cost", scalar(output.path("estimated_cost")));
            } else if ("PLANNER_ATTEMPT".equals(type) && "dag_planner".equals(row.get("name"))) {
                JsonNode input = json(row.get("input_json")), output = json(row.get("output_json"));
                long attempt = input.path("attempt").asLong(0);
                if (attempt <= 0 || !output.isObject()) continue;
                Entry entry = entries.computeIfAbsent(attempt, Entry::new);
                entry.metadata(input, output);
                entry.values.put("result_seq", row.get("seq"));
                entry.values.put("retry_reason", scalar(input.path("retry_reason")));
                for (String key : List.of("call_status", "validation_status", "result", "call_error", "validation_error")) {
                    if (output.hasNonNull(key)) entry.values.put(key, scalar(output.get(key)));
                }
                entry.complete = true;
                entry.logical = output.path("logical_plan");
                entry.compiled = output.path("compiled_plan");
            } else if (current != null && !current.complete) {
                // 历史记录只能利用已记录的错误；没有终态时保留 UNKNOWN，不能追认模型或校验通过。
                if ("PLAN".equals(type) && nonEmpty(row.get("error"))) {
                    current.values.put("validation_status", "FAILED");
                    current.values.put("validation_error", row.get("error"));
                    current.values.put("result", "REPAIR_REQUIRED");
                }
                if ("PLAN".equals(type) && !nonEmpty(row.get("error"))) {
                    JsonNode envelope = json(row.get("output_json"));
                    if (envelope.path("plan").isObject()) current.logical = envelope.path("plan");
                }
                if ("PLAN_COMPILED".equals(type)) current.compiled = json(row.get("output_json"));
                if ("FOLLOWUP_PLAN".equals(type)) current.legacyReady = true;
            }
        }
        Entry last = entries.isEmpty() ? null : entries.lastEntry().getValue();
        boolean ready = last != null && (last.complete
                ? "EXECUTABLE".equals(last.values.get("result")) && "PASSED".equals(last.values.get("validation_status"))
                : last.legacyReady && !"FAILED".equals(last.values.get("validation_status")));
        return new PlannerTraceView(entries.values().stream().map(e -> e.values).toList(),
                ready && last.logical.isObject() ? last.logical : null,
                ready && last.compiled.isObject() ? last.compiled : null);
    }

    private static class Entry {
        final Map<String, Object> values = new LinkedHashMap<>();
        JsonNode logical = MAPPER.nullNode(), compiled = MAPPER.nullNode();
        boolean complete, legacyReady;
        Entry(long attempt) {
            values.put("attempt", attempt);
            for (String key : List.of("model", "model_role", "retry_reason", "call_error", "validation_error")) values.put(key, null);
            values.put("call_status", "UNKNOWN");
            values.put("validation_status", "UNKNOWN");
            values.put("result", "UNKNOWN");
        }
        void metadata(JsonNode input, JsonNode output) {
            for (String key : List.of("model", "model_role")) {
                if (values.get(key) != null) continue; // 实际 LLM 请求记录优先，不能由终态元数据覆盖。
                JsonNode value = input.hasNonNull(key) ? input.get(key) : output.path(key);
                if (value.isTextual() && !value.asText().isBlank()) values.put(key, value.asText());
            }
        }
    }

    private static JsonNode json(Object value) {
        if (value == null) return MAPPER.nullNode();
        try {
            if (value instanceof JsonNode node) return node;
            if (value instanceof String text) {
                JsonNode parsed = MAPPER.readTree(text);
                return parsed == null ? MAPPER.nullNode() : parsed;
            }
            if (value instanceof byte[] bytes) {
                JsonNode parsed = MAPPER.readTree(bytes);
                return parsed == null ? MAPPER.nullNode() : parsed;
            }
            return MAPPER.valueToTree(value);
        } catch (Exception ignored) { return MAPPER.nullNode(); }
    }
    private static Object scalar(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? null : MAPPER.convertValue(value, Object.class);
    }
    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }
    private static boolean nonEmpty(Object value) { return value != null && !String.valueOf(value).isBlank(); }
}
