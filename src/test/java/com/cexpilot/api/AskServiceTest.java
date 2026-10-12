package com.cexpilot.api;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.conversation.ConversationService;
import com.cexpilot.dag.DagPlan;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.ConditionOrigin;
import com.cexpilot.metric.EffectiveTarget;
import com.cexpilot.metric.LogicalPlan;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.trace.DbTraceSink;
import com.cexpilot.trace.TraceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationService conversation = mock(ConversationService.class);
    private final com.cexpilot.conversation.ConversationContextService contextService =
            mock(com.cexpilot.conversation.ConversationContextService.class);
    private final DagRuntime runtime = mock(DagRuntime.class);
    private final TraceRepository traceRepository = mock(TraceRepository.class);
    private final DbTraceSink traceSink = mock(DbTraceSink.class);
    private final AskService service;

    private final ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "turn-1", 7);
    /** 与构造 AskService 时的固定时钟一致。 */
    private static final Instant T0 = Instant.parse("2026-10-12T00:00:00Z");

    AskServiceTest() {
        LlmConfig llmConfig = new LlmConfig();
        llmConfig.getNormal().setModel("qwen-plus");
        ObjectProvider<BuildProperties> noBuildProps = mock(ObjectProvider.class);
        service = new AskService(conversation, contextService, runtime, traceRepository, traceSink, llmConfig,
                Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC), noBuildProps);
    }

    @BeforeEach
    void setUp() {
        when(conversation.getOrCreateConversation(any(), anyString(), any())).thenReturn("conv-1");
        when(conversation.beginTurn(eq("conv-1"), anyString(), any(), any())).thenReturn(turn);
        when(conversation.renderContext("conv-1")).thenReturn("");
    }

    private ExecutionResult successResult() {
        return new ExecutionResult("答案", null, 2, 1, 10, 20);
    }

    private void stubRuntimeSuccess() {
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(successResult());
    }

    @Test
    void 成功路径登记并提交轮次且响应带turnId() {
        stubRuntimeSuccess();

        AskResponse response = service.ask("conv-1", "BTC 多少钱", "v1", null, "parent-1", null);

        assertEquals("turn-1", response.turnId());
        assertFalse(response.busy());
        assertEquals("答案", response.answer());
        verify(conversation).beginTurn("conv-1", "BTC 多少钱", "parent-1", T0);
        verify(conversation).finishTurn("conv-1", turn, "SUCCEEDED", "答案", response.traceId(), null, null);
        verify(traceRepository).finishTrace(eq(response.traceId()), eq("SUCCESS"), eq("答案"),
                anyInt(), anyInt(), anyInt(), anyInt(), any(), anyLong(), isNull());
    }

    @Test
    void 会话忙碌时返回忙碌文案且不执行DAG不建trace() {
        when(conversation.beginTurn(eq("conv-1"), anyString(), any(), any())).thenReturn(null);

        AskResponse response = service.ask("conv-1", "BTC 多少钱", "v1", null, null, null);

        assertTrue(response.answer().contains("还在处理中"));
        assertTrue(response.busy());
        assertNull(response.traceId());
        assertNull(response.turnId());
        verify(runtime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).startTrace(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void 执行失败时轮次落FAILED且不覆盖frame() {
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM 超时"));

        assertThrows(RuntimeException.class,
                () -> service.ask("conv-1", "BTC 多少钱", "v1", null, null, null));

        verify(traceRepository).finishTrace(anyString(), eq("FAILED"), isNull(), anyInt(), anyInt(),
                isNull(), isNull(), isNull(), anyLong(), eq("LLM 超时"));
        verify(conversation).finishTurn(eq("conv-1"), eq(turn), eq("FAILED"), isNull(), anyString(),
                isNull(), isNull());
    }

    @Test
    void 流式meta事件携带turnId() {
        stubRuntimeSuccess();
        List<String> metas = new ArrayList<>();

        service.ask("conv-1", "BTC 多少钱", "v1", null, null, new AskService.AskStreamListener() {
            @Override
            public void onMeta(String conversationId, String traceId, String turnId) {
                metas.add(conversationId + "/" + traceId + "/" + turnId);
            }

            @Override
            public void onProgress(String text) {
            }

            @Override
            public void onDelta(String text) {
            }

            @Override
            public void onDone(AskResponse response) {
            }
        });

        assertEquals(1, metas.size());
        assertTrue(metas.get(0).startsWith("conv-1/"));
        assertTrue(metas.get(0).endsWith("/turn-1"));
    }

    @Test
    void 不传parentTurnId时正常登记() {
        stubRuntimeSuccess();

        AskResponse response = service.ask("conv-1", "BTC 多少钱", "v1", null, null);

        assertEquals("turn-1", response.turnId());
        verify(conversation).beginTurn("conv-1", "BTC 多少钱", null, T0);
    }

    @Test
    void 成功轮次把编译产物落成frame与logicalPlanJson() throws Exception {
        JsonNode planJson = MAPPER.readTree("{\"metrics\":[{\"id\":\"m1\"}],\"calculations\":[]}");
        EffectiveTarget target = new EffectiveTarget("m1", "metric_0", "binance", ConditionOrigin.EXPLICIT,
                "price.high", "range_statistic",
                MAPPER.readTree("{\"market_type\":\"perpetual\",\"base\":\"BTC\",\"quote\":\"USDT\",\"settle\":\"USDT\"}"),
                ConditionOrigin.DEFAULT,
                MAPPER.readTree("{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1}"),
                null, ConditionOrigin.DEFAULT, false, ConditionOrigin.DEFAULT,
                null, null, null, "USDT", "kline", "v1");
        CompiledPlan compiledPlan = new CompiledPlan(new LogicalPlan(List.of(), List.of()), planJson,
                new DagPlan(List.of()), Map.of("m1", Map.of("binance", "metric_0")), List.of(target));
        var evidence = MAPPER.createArrayNode();
        var entry = evidence.addObject();
        entry.put("node_id", "metric_0").put("type", "metric").put("ok", true);
        entry.set("identity", MAPPER.readTree("{\"metric\":\"price.high\"}"));
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Consumer<CompiledPlan> sink = (Consumer<CompiledPlan>) inv.getArgument(7);
                    sink.accept(compiledPlan);
                    return new ExecutionResult("答案", evidence, 1, 1, 10, 20);
                });

        AskResponse response = service.ask("conv-1", "昨天 BTC 最高价", "v1", "Asia/Shanghai", null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        var frameCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversation).finishTurn(eq("conv-1"), eq(turn), eq("SUCCEEDED"), eq("答案"),
                eq(response.traceId()), captor.capture(), frameCaptor.capture());
        assertEquals(planJson.toString(), captor.getValue());

        JsonNode frame = MAPPER.readTree(frameCaptor.getValue());
        assertEquals("conv-1", frame.path("conversationId").asText());
        assertEquals("turn-1", frame.path("sourceTurnId").asText());
        assertEquals("昨天 BTC 最高价", frame.path("originalQuestion").asText());
        assertEquals("2026-10-12T00:00:00Z", frame.path("requestTime").asText());
        assertEquals("Asia/Shanghai", frame.path("timezone").asText());
        assertEquals("turn-1", frame.path("lastAttempt").path("turnId").asText());
        assertEquals("SUCCEEDED", frame.path("lastAttempt").path("status").asText());
        JsonNode frameTarget = frame.path("targets").get(0);
        assertEquals("metric_0", frameTarget.path("physicalId").asText());
        assertEquals("BTC", frameTarget.path("instrument").path("base").asText());
        assertEquals("DEFAULT", frameTarget.path("settleOrigin").asText());
        assertEquals("DEFAULT", frameTarget.path("intervalOrigin").asText());
        assertEquals("calendar_period", frameTarget.path("timeExpression").path("type").asText());
        JsonNode focusNode = frame.path("focus").path("evidence_nodes").get(0);
        assertEquals("metric_0", focusNode.path("node_id").asText());
        assertEquals("price.high", focusNode.path("identity").path("metric").asText());
    }
}
