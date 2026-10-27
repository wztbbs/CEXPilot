package com.cexpilot.conversation;

import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 查询状态（设计文档 5.1）：一项正在讨论的查询任务，不是聊天记录摘要。
 * 有效计划状态保存在 TaskState，按稳定任务 ID 和版本写入独立任务及轮次快照；
 * TaskState 另保留最近成功状态及未完成链，下一轮按 Planner 选中的任务版本继承。
 *
 * @param requestTime 请求时间的 ISO-8601 表示（UTC）；timezone 为请求携带的用户时区（null = 未携带）
 */
public record QueryFrame(
        String frameId,
        String conversationId,
        long version,
        String sourceTurnId,
        String originalQuestion,
        String requestTime,
        String timezone,
        List<EffectiveTarget> targets,
        JsonNode focus,
        JsonNode pendingClarification,
        LastAttempt lastAttempt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QueryFrame {
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (focus != null && focus.isNull()) focus = null;
        if (pendingClarification != null && pendingClarification.isNull()) pendingClarification = null;
    }

    /** 最近尝试（设计文档 5.4 lastAttempt）：哪一轮、以什么状态结束。 */
    public record LastAttempt(String turnId, String status) {
    }

    /**
     * 从编译产物建 frame：targets 按节点保存（多对象不压成全局条件）；
     * focus 首期只存 evidence 的描述性引用（节点 id + 指标身份），不复制明细数据。
     */
    public static QueryFrame fromCompiledPlan(String conversationId, String turnId, String question,
                                              Instant requestTime, ZoneId userZone,
                                              CompiledPlan plan, JsonNode evidence) {
        return fromCompiledPlan(UUID.randomUUID().toString(), 1, conversationId, turnId, question,
                requestTime, userZone, plan, evidence, "SUCCEEDED");
    }

    public static QueryFrame fromCompiledPlan(String frameId, long version, String conversationId,
            String turnId, String question, Instant requestTime, ZoneId userZone, CompiledPlan plan,
            JsonNode evidence, String status) {
        return new QueryFrame(frameId, conversationId, version, turnId, question,
                requestTime == null ? null : requestTime.toString(),
                userZone == null ? null : userZone.getId(), targetsFromEvidence(plan, evidence, turnId, frameId, version), focusFromEvidence(evidence), null,
                new LastAttempt(turnId, status));
    }

    /** 回填每个真实指标节点的执行元数据；不复制 payload，也不把自动 5m 覆盖为用户要求的 interval。 */
    private static List<EffectiveTarget> targetsFromEvidence(CompiledPlan plan, JsonNode evidence,
            String turnId, String frameId, long version) {
        java.util.Map<String, JsonNode> byNode = new java.util.HashMap<>();
        if (evidence != null && evidence.isArray()) {
            for (JsonNode entry : evidence) {
                if (entry.path("ok").asBoolean(false) && entry.path("data").isObject()) {
                    byNode.put(entry.path("node_id").asText(), entry.path("data"));
                }
            }
        }
        return plan.targets().stream().map(target -> {
            var sources = new java.util.LinkedHashMap<String, com.cexpilot.metric.ConditionSource>();
            target.conditionSources().forEach((field, source) -> sources.put(field, source.identify(turnId, frameId, version)));
            ObjectNode execution = MAPPER.createObjectNode();
            JsonNode data = byNode.get(target.physicalId());
            if (data != null) {
                for (String field : List.of("candle_interval", "interval_source", "requested_range", "effective_range",
                        "actual_range", "coverage", "as_of", "timezone", "requested_count", "actual_count", "sample_complete",
                        "sample_selection", "selection_range", "start_sample_at", "end_sample_at")) {
                    if (data.hasNonNull(field)) execution.set(field, data.get(field).deepCopy());
                }
                // Provider 为完整窗口可能省略相同的 effective_range；持久化仍明确保留两者。
                if (!execution.has("effective_range") && execution.has("requested_range")) {
                    execution.set("effective_range", execution.get("requested_range").deepCopy());
                }
            }
            return target.withMetadata(sources, execution.isEmpty() ? null : execution);
        }).toList();
    }

    /** focus 的描述性引用：只留节点身份与成功标记，完整数据仍在 trace / evidence。 */
    static JsonNode focusFromEvidence(JsonNode evidence) {
        ObjectNode focus = MAPPER.createObjectNode();
        ArrayNode nodes = focus.putArray("evidence_nodes");
        if (evidence != null && evidence.isArray()) {
            for (JsonNode entry : evidence) {
                ObjectNode node = nodes.addObject();
                node.put("node_id", entry.path("node_id").asText(null));
                node.put("type", entry.path("type").asText(null));
                node.put("ok", entry.path("ok").asBoolean(false));
                if (entry.has("identity")) {
                    node.set("identity", entry.get("identity"));
                }
                if (entry.has("operator")) {
                    node.put("operator", entry.path("operator").asText());
                }
                // 阶段二：本轮新产生/复用的 factId 随 focus 落 frame，供下一轮事实索引与审计对照
                if (entry.has("fact_id")) {
                    node.put("fact_id", entry.path("fact_id").asText());
                }
                if (entry.has("reused")) {
                    node.put("reused", entry.path("reused").asBoolean());
                }
                if (entry.has("source_turn_id")) node.set("source_turn_id", entry.get("source_turn_id"));
            }
        }
        return focus;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("QueryFrame 序列化失败", e);
        }
    }

    /** 从持久化 JSON 还原；失败抛 IllegalArgumentException，由调用方决定降级策略。 */
    public static QueryFrame fromJson(String json) {
        try {
            return MAPPER.readValue(json, QueryFrame.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("QueryFrame 反序列化失败: " + e.getMessage(), e);
        }
    }
}
