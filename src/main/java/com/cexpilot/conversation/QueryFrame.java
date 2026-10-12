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
 * 成功轮次提交时持久化到 conversation_state.active_frame_json（失败/澄清轮次不覆盖）；
 * 下一轮由 ConversationContextService 读取注入 Planner prompt，并由 InheritanceValidator 做继承对账（阶段一 c）。
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
        return new QueryFrame(UUID.randomUUID().toString(), conversationId, 1, turnId, question,
                requestTime == null ? null : requestTime.toString(),
                userZone == null ? null : userZone.getId(),
                plan.targets(), focusFromEvidence(evidence), null, new LastAttempt(turnId, "SUCCEEDED"));
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
