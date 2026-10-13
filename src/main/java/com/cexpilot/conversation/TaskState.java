package com.cexpilot.conversation;

import com.cexpilot.metric.CompiledPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 一项独立任务的完整版本：有效条件、最近成功结果，以及尚未完成的澄清/失败链。 */
public record TaskState(String frameId, String conversationId, long version, String originQuestion,
                        long firstQueryNo, long lastQueryNo, String turnId, String status,
                        String requestTime, String timezone, QueryFrame frame, QueryFrame successfulFrame, JsonNode logicalPlan,
                        List<Dialogue> dialogue) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Dialogue(String turnId, String question, String response) {}

    public TaskState {
        dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
        if (logicalPlan != null && logicalPlan.isNull()) logicalPlan = null;
    }

    public static TaskState legacy(QueryFrame frame, long queryNo, String question) {
        return new TaskState(frame.frameId(), frame.conversationId(), frame.version(), question,
                queryNo, queryNo, frame.sourceTurnId(), "SUCCEEDED", frame.requestTime(), frame.timezone(),
                frame, frame, null, List.of());
    }

    public static TaskState complete(String frameId, String conversationId, long version, TaskState base,
            ConversationService.TurnHandle turn, String question, String status, String response,
            CompiledPlan plan, JsonNode evidence, Instant requestTime, ZoneId zone) {
        String origin = base == null ? question : base.originQuestion();
        QueryFrame effective = base == null ? null : base.frame();
        QueryFrame success = base == null ? null : base.successfulFrame();
        JsonNode logical = base == null ? null : base.logicalPlan();
        if (plan != null) {
            effective = QueryFrame.fromCompiledPlan(frameId, version, conversationId, turn.turnId(), question,
                    requestTime, zone, plan, evidence, status);
            logical = plan.logicalPlanJson();
            if ("SUCCEEDED".equals(status)) success = effective;
        }
        String taskStatus = status;
        // 回答“为什么要多少期”等说明只完成这一轮问答，没有完成原查询任务。
        if (plan == null && "SUCCEEDED".equals(status) && base != null && !base.dialogue().isEmpty()) {
            taskStatus = base.status();
        }
        List<Dialogue> chain = new ArrayList<>(base == null ? List.of() : base.dialogue());
        if ("NEEDS_CLARIFICATION".equals(taskStatus) || "FAILED".equals(taskStatus)) {
            chain.add(new Dialogue(turn.turnId(), question, response));
        } else {
            chain.clear();
        }
        return new TaskState(frameId, conversationId, version, origin,
                base == null ? turn.queryNo() : base.firstQueryNo(), turn.queryNo(), turn.turnId(), taskStatus,
                requestTime.toString(), zone == null ? null : zone.getId(), effective, success, logical, chain);
    }

    public TaskState withSuccessfulFrame(QueryFrame success) {
        return new TaskState(frameId, conversationId, version, originQuestion, firstQueryNo, lastQueryNo,
                turnId, status, requestTime, timezone, frame, success, logicalPlan, dialogue);
    }

    public String toJson() {
        try { return MAPPER.writeValueAsString(this); }
        catch (Exception e) { throw new IllegalStateException("任务状态序列化失败", e); }
    }

    public static TaskState fromJson(String json) {
        try { return MAPPER.readValue(json, TaskState.class); }
        catch (Exception e) { throw new IllegalArgumentException("任务状态解析失败", e); }
    }
}
