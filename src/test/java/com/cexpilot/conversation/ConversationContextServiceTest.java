package com.cexpilot.conversation;

import com.cexpilot.metric.ConditionOrigin;
import com.cexpilot.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结构化上下文组装（阶段一 c）：有 frame 时分层注入、文本历史带发生时间、
 * frame 损坏时降级为纯文本旧行为。
 */
class ConversationContextServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final ConversationContextService service = new ConversationContextService(repository);

    private static QueryFrame sampleFrame() throws Exception {
        JsonNode instrument = MAPPER.readTree(
                "{\"market_type\":\"perpetual\",\"base\":\"BTC\",\"quote\":\"USDT\",\"settle\":\"USDT\"}");
        JsonNode time = MAPPER.readTree("{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1}");
        EffectiveTarget target = new EffectiveTarget("m1", "metric_0", "binance", ConditionOrigin.EXPLICIT,
                "price.high", "range_statistic", instrument, ConditionOrigin.DEFAULT,
                time, "5m", ConditionOrigin.DEFAULT, false, ConditionOrigin.DEFAULT,
                null, null, null, "USDT", "kline", "v1");
        return new QueryFrame("frame-1", "conv-1", 1, "turn-1", "昨天币安 BTC 最高价",
                Instant.parse("2026-10-12T01:26:53Z").toString(), "Asia/Shanghai",
                List.of(target), null, null, null);
    }

    private static QueryRecord record(String q, String a) {
        return new QueryRecord(1, q, a, "trace-1", LocalDateTime.of(2026, 10, 11, 9, 26));
    }

    @Test
    void 有frame时分层注入结构化条件与文本历史() throws Exception {
        when(repository.findActiveFrameJson("conv-1")).thenReturn(sampleFrame().toJson());
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of(record("昨天币安 BTC 最高价", "最高 83955 USDT")));

        PlannerInput input = service.buildPlannerInput("conv-1");

        assertNotNull(input.activeFrame());
        String ctx = input.contextText();
        assertTrue(ctx.contains("【活动查询状态】"), ctx);
        assertTrue(ctx.contains("price.high"), ctx);
        assertTrue(ctx.contains("binance"), ctx);
        assertTrue(ctx.contains("5m"), ctx);
        assertTrue(ctx.contains("【近期问答】"), ctx);
        // 文本历史带每轮发生时间（缓解跨天窗口漂移）
        assertTrue(ctx.contains("2026-10-11 09:26"), ctx);
        assertTrue(ctx.contains("最高 83955 USDT"), ctx);
    }

    @Test
    void frame损坏时降级为纯文本历史不报错() {
        when(repository.findActiveFrameJson("conv-1")).thenReturn("{损坏的json");
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of(record("BTC 多少钱", "83500")));

        PlannerInput input = service.buildPlannerInput("conv-1");

        assertNull(input.activeFrame());
        assertFalse(input.contextText().contains("【活动查询状态】"));
        assertTrue(input.contextText().contains("【近期问答】"), input.contextText());
    }

    @Test
    void 无frame无历史时上下文为空且activeFrame为null() {
        when(repository.findActiveFrameJson("conv-1")).thenReturn(null);
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of());

        PlannerInput input = service.buildPlannerInput("conv-1");

        assertNull(input.activeFrame());
        assertTrue(input.contextText().isBlank());
    }

    @Test
    void 首轮澄清任务独立渲染不要求frame存在() {
        // R1：新会话没有活动 frame，澄清任务也要进 prompt，且带原问题
        when(repository.findActiveFrameJson("conv-1")).thenReturn(null);
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of());
        when(repository.findPendingTaskJson("conv-1")).thenReturn(
                "{\"question\":\"BTC 和 ETH 谁的费率均值更高？\",\"clarify\":\"您想看哪家交易所？\",\"turnId\":\"t1\"}");

        PlannerInput input = service.buildPlannerInput("conv-1");

        assertNull(input.activeFrame());
        String ctx = input.contextText();
        assertTrue(ctx.contains("【待补条件】"), ctx);
        assertTrue(ctx.contains("BTC 和 ETH 谁的费率均值更高？"), "原问题必须回传给 Planner");
        assertTrue(ctx.contains("您想看哪家交易所？"), ctx);
        assertTrue(ctx.contains("完成原任务"), ctx);
    }

    @Test
    void 失败尝试独立渲染供重试接续() {
        // R2：FAILED 轮次不进文本历史，但"再试一次"需要结构化失败任务
        when(repository.findActiveFrameJson("conv-1")).thenReturn(null);
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of());
        when(repository.findLastAttemptJson("conv-1")).thenReturn(
                "{\"turnId\":\"t2\",\"question\":\"ETH 昨日收盘价\",\"status\":\"FAILED\"}");

        PlannerInput input = service.buildPlannerInput("conv-1");

        String ctx = input.contextText();
        assertTrue(ctx.contains("【最近失败尝试】"), ctx);
        assertTrue(ctx.contains("ETH 昨日收盘价"), ctx);
        assertTrue(ctx.contains("以它为基准"), ctx);
    }

    @Test
    void 澄清任务损坏时忽略该段不报错() {
        when(repository.findActiveFrameJson("conv-1")).thenReturn(null);
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of());
        when(repository.findPendingTaskJson("conv-1")).thenReturn("{损坏");

        PlannerInput input = service.buildPlannerInput("conv-1");

        assertFalse(input.contextText().contains("【待补条件】"));
    }

    @Test
    void 答案截断放宽到500且结构化条件不参与截断() throws Exception {
        String longAnswer = "数".repeat(600);
        when(repository.findActiveFrameJson("conv-1")).thenReturn(sampleFrame().toJson());
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of(record("q", longAnswer)));

        String ctx = service.buildPlannerInput("conv-1").contextText();

        assertFalse(ctx.contains("数".repeat(501)), "答案应被截断到 500 字");
        assertTrue(ctx.contains("数".repeat(500)), "前 500 字应保留");
        assertTrue(ctx.contains("price.high"), "结构化条件不受文本截断影响");
    }

    @Test
    void 有可引用历史事实时渲染候选段且不受文本截断影响() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(
                new com.cexpilot.evidence.InMemoryEvidenceRepository());
        QueryFrame frame = sampleFrame();
        EffectiveTarget target = frame.targets().get(0);
        JsonNode payload = MAPPER.readTree("""
                {"metric":"price.high","exchange":"binance","query_shape":"range_statistic","unit":"USDT",
                 "requested_range":{"start_inclusive":"2026-10-11 00:00:00","end_exclusive":"2026-10-12 00:00:00",
                 "timezone":"Asia/Shanghai"},"value":83955}
                """);
        // 本例验证候选渲染，获取时间保持在保留期内，避免固定日期经过 24 小时后失效。
        store.saveFact("conv-1", "turn-1", "trace-1", target, payload, Instant.now());
        var withEvidence = new ConversationContextService(repository, store);
        String longAnswer = "数".repeat(600);
        when(repository.findActiveFrameJson("conv-1")).thenReturn(frame.toJson());
        when(repository.recentQueries("conv-1", 5)).thenReturn(List.of(record("q", longAnswer)));

        PlannerInput input = withEvidence.buildPlannerInput("conv-1");

        assertEquals(1, input.evidence().size());
        assertEquals("fact_1", input.evidence().get(0).ref());
        String ctx = input.contextText();
        assertTrue(ctx.contains("【可引用历史事实】"), ctx);
        assertTrue(ctx.contains("ref=fact_1"), ctx);
        assertTrue(ctx.contains("price.high"), ctx);
        assertFalse(ctx.contains("83955"), "候选索引不含 payload 明细数值");
        assertTrue(ctx.contains("数".repeat(500)), "文本历史仍按原口径截断，事实段优先保留");
        // 其它会话的事实不进本会话候选
        assertTrue(service.buildPlannerInput("conv-1").evidence().isEmpty(), "无 store 时旧行为：无候选段");
    }
}
