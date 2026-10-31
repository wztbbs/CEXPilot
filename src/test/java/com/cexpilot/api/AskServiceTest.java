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
import static org.mockito.ArgumentMatchers.nullable;
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
        when(contextService.buildPlannerInput("conv-1")).thenReturn(new com.cexpilot.conversation.PlannerInput("", null));
        when(conversation.finishTaskTurn(anyString(), any(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(new ConversationService.TaskCommit(false, null, 0, null));
    }

    private ExecutionResult successResult() {
        return new ExecutionResult("答案", null, 2, 1, 10, 20);
    }

    private void stubRuntimeSuccess() {
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(successResult());
    }

    @Test
    void 表单回执按key传入规划且保留未验证的原值用于纠错() throws Exception {
        var payload = MAPPER.readTree("{\"status\":\"NEEDS_CLARIFICATION\",\"clarify\":[{\"key\":\"count\",\"label\":\"期数\",\"type\":\"number\",\"min\":1,\"max\":100}]}");
        when(conversation.history("conv-1")).thenReturn(List.of(new com.cexpilot.conversation.QueryRecord(2,"比较Funding","请补期数","trace",null,"parent","NEEDS_CLARIFICATION","query",payload)));
        var pending = com.cexpilot.conversation.TaskState.complete("frame","conv-1",1,null,
                new ConversationService.TurnHandle(2,"parent",1),"比较Funding","NEEDS_CLARIFICATION","请补期数",null,null,T0,ZoneOffset.UTC);
        when(contextService.buildPlannerInput("conv-1")).thenReturn(new com.cexpilot.conversation.PlannerInput("",null,List.of(),0,List.of(),pending));
        var correction = MAPPER.readTree("{\"status\":\"NEEDS_CLARIFICATION\",\"clarify\":[{\"key\":\"count\",\"label\":\"期数\",\"type\":\"number\",\"reason\":\"invalid\",\"message\":\"最多100期，您填写200\",\"value\":\"200\"}]}");
        when(runtime.execute(anyString(),anyString(),anyString(),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv -> {
            com.cexpilot.conversation.PlannerInput input = inv.getArgument(8);
            assertTrue(input.clarificationSubmission());
            assertEquals("200",input.currentTask().clarificationAnswers().get(0).value().asText());
            assertTrue(input.contextText().contains("count（期数）=200"));
            return new ExecutionResult("最多100期，您填写200",null,0,0,10,5,"NEEDS_CLARIFICATION","最多100期，您填写200")
                    .withTaskScope(com.cexpilot.conversation.TaskScope.current(input.currentTask())).withAgent("query",correction);
        });
        when(conversation.finishQueryTurn(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new ConversationService.TaskCommit(true,"frame",2,null));
        var response = service.ask("conv-1","补充本轮查询条件：期数200","v1",null,"parent",null,java.util.Map.of("count","200"));
        assertEquals("invalid",response.clarify().get(0).reason());
        assertTrue(response.clarify().get(0).message().contains("最多100期"));
        assertEquals("200",response.clarify().get(0).value().asText());
    }

    @Test
    void 无路由兼容入口也一起提交澄清表单和任务() {
        var payload = MAPPER.createObjectNode().put("status", "NEEDS_CLARIFICATION");
        payload.putArray("clarify").addObject().put("key", "base").put("label", "币种").put("type", "text");
        var result = new ExecutionResult("请补充币种", null, 0, 0, 10, 5, "NEEDS_CLARIFICATION", "请补充币种")
                .withAgent("query", payload);
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);
        when(conversation.finishQueryTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ConversationService.TaskCommit(true, "frame", 1, null));
        var response = service.ask("conv-1", "比较两所Funding", "v1");
        assertEquals("base", response.clarify().get(0).key());
        verify(conversation).finishQueryTurn(eq("conv-1"), eq(turn), eq("比较两所Funding"), eq("NEEDS_CLARIFICATION"),
                eq("请补充币种"), any(), isNull(), isNull(), any(), any(), any(), eq("请补充币种"), eq(payload));
    }

    @Test
    void 混合模型费用按实际调用记录汇总而不是全部按普通模型单价() {
        var usages = List.of(new com.cexpilot.llm.LlmUsage("normal","one",10,5,0.02),
                new com.cexpilot.llm.LlmUsage("flagship","two",10,5,0.2),
                new com.cexpilot.llm.LlmUsage("normal","one",10,5,0.02));
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutionResult("答案",null,1,1,30,15).withLlmUsages(usages));
        service.ask("conv-1","查询","v1",null,null,null);
        var cost = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(traceRepository).finishTrace(anyString(),eq("SUCCESS"),eq("答案"),anyInt(),anyInt(),eq(30),eq(15),
                cost.capture(),anyLong(),isNull(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt());
        assertEquals(0.24,cost.getValue(),1e-12);
    }

    @Test
    void 第二模型失败时保留先前token但总费用保持未知() {
        var usages = List.of(new com.cexpilot.llm.LlmUsage("normal","one",10,5,0.02),
                new com.cexpilot.llm.LlmUsage("flagship","two",null,null,null));
        var counters = new DagRuntime.TurnCounters(2,0,0,0,0,0,List.of()).withLlmUsages(usages);
        when(runtime.execute(anyString(),anyString(),anyString(),any(),any(),any(),any(),any(),any(),any()))
                .thenThrow(new DagRuntime.ExecutionFailure(new IllegalStateException("模型2超时"),counters));
        assertThrows(DagRuntime.ExecutionFailure.class,() -> service.ask("conv-1","查询","v1",null,null,null));
        verify(traceRepository).finishTrace(anyString(),eq("FAILED"),isNull(),anyInt(),anyInt(),eq(10),eq(5),
                isNull(),anyLong(),eq("模型2超时"),eq(0),eq(2),eq(0),eq(0),eq(0),eq(0));
    }

    @Test
    void 成功路径登记并提交轮次且响应带turnId() {
        stubRuntimeSuccess();

        AskResponse response = service.ask("conv-1", "BTC 多少钱", "v1", null, "parent-1", null);

        assertEquals("turn-1", response.turnId());
        assertFalse(response.busy());
        assertEquals("答案", response.answer());
        verify(conversation).beginTurn("conv-1", "BTC 多少钱", "parent-1", T0);
        verify(conversation).finishTaskTurn(eq("conv-1"), eq(turn), eq("BTC 多少钱"), eq("SUCCEEDED"),
                eq("答案"), eq(response.traceId()), isNull(), isNull(), isNull(), eq(T0), isNull(), isNull());
        verify(traceRepository).finishTrace(eq(response.traceId()), eq("SUCCESS"), eq("答案"),
                anyInt(), anyInt(), anyInt(), anyInt(), any(), anyLong(), isNull(), eq(0),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void 陈旧父轮次不读取上下文不建trace不调用LLM() {
        when(conversation.beginTurn(eq("conv-1"), anyString(), any(), any()))
                .thenThrow(new com.cexpilot.conversation.StaleContextException("conv-1", "new-turn"));
        assertThrows(com.cexpilot.conversation.StaleContextException.class,
                () -> service.ask("conv-1", "它现在呢", "v1", null, "old-turn", null));
        org.mockito.Mockito.verifyNoInteractions(runtime, traceRepository, contextService);
    }

    @Test
    void 执行失败提交后确认客户端可以继续使用该轮次() {
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("LLM 超时"));
        AskService.AskStreamListener listener = mock(AskService.AskStreamListener.class);
        assertThrows(IllegalStateException.class,
                () -> service.ask("conv-1", "查询", "v1", null, null, listener));
        verify(listener).onTurnFinished("turn-1");
    }

    @Test
    void 请求来源按每次请求写入trace且旧调用默认用户流量() {
        stubRuntimeSuccess();
        service.ask("conv-1", "BTC 多少钱", "v1", null, null, null);
        verify(traceRepository).startTrace(anyString(), eq("conv-1"), eq("BTC 多少钱"),
                anyString(), nullable(String.class), eq("v1"), nullable(String.class), eq(true));
        service.ask("conv-1", "ETH 多少钱", "v1", null, null, null, Map.of(), false);
        verify(traceRepository).startTrace(anyString(), eq("conv-1"), eq("ETH 多少钱"),
                anyString(), nullable(String.class), eq("v1"), nullable(String.class), eq(false));
        verify(conversation).getOrCreateConversation("conv-1", "ETH 多少钱", "v1");
    }

    @Test
    void 会话忙碌时返回忙碌文案且不执行DAG不建trace() {
        when(conversation.beginTurn(eq("conv-1"), anyString(), any(), any())).thenReturn(null);

        AskResponse response = service.ask("conv-1", "BTC 多少钱", "v1", null, null, null);

        assertTrue(response.answer().contains("还在处理中"));
        assertTrue(response.busy());
        assertNull(response.traceId());
        assertNull(response.turnId());
        verify(runtime, never()).execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).startTrace(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void 执行失败时轮次落FAILED且带失败任务快照() {
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM 超时"));

        assertThrows(RuntimeException.class,
                () -> service.ask("conv-1", "ETH 昨日收盘价", "v1", null, null, null));

        verify(traceRepository).finishTrace(anyString(), eq("FAILED"), isNull(), anyInt(), anyInt(),
                isNull(), isNull(), isNull(), anyLong(), eq("LLM 超时"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        var scope = org.mockito.ArgumentCaptor.forClass(com.cexpilot.conversation.TaskScope.class);
        verify(conversation).finishTaskTurn(eq("conv-1"), eq(turn), eq("ETH 昨日收盘价"), eq("FAILED"),
                isNull(), anyString(), isNull(), isNull(), scope.capture(), eq(T0), any(), eq("LLM 超时"));
        assertEquals(com.cexpilot.conversation.TaskScope.Action.NEW, scope.getValue().action());
    }

    @Test
    void 建trace失败时轮次仍收尾且不写trace事件() {
        // R8：beginTurn 之后、startTrace 本身失败（如 DB 抖动），轮次必须落 FAILED 释放占用
        // startTrace 返回 void，桩要用 doThrow 语法；promptVersion/buildCommit 来自 mock 可能为 null
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(traceRepository).startTrace(anyString(), anyString(), anyString(),
                        anyString(), nullable(String.class), any(), nullable(String.class), org.mockito.ArgumentMatchers.anyBoolean());

        assertThrows(IllegalStateException.class,
                () -> service.ask("conv-1", "BTC 多少钱", "v1", null, null, null));

        verify(conversation).finishTaskTurn(eq("conv-1"), eq(turn), eq("BTC 多少钱"), eq("FAILED"), isNull(),
                anyString(), isNull(), isNull(), any(), eq(T0), any(), eq("db down"));
        // trace 行不存在，不应对它 finishTrace / 落事件
        verify(traceRepository, never()).finishTrace(anyString(), anyString(), any(), anyInt(), anyInt(),
                any(), any(), any(), anyLong(), any());
    }

    @Test
    void onDone推送失败不改写已成功的轮次() {
        stubRuntimeSuccess();

        AskResponse response = service.ask("conv-1", "BTC 多少钱", "v1", null, null,
                new AskService.AskStreamListener() {
                    @Override
                    public void onMeta(String conversationId, String traceId, String turnId) {
                    }

                    @Override
                    public void onProgress(String text) {
                    }

                    @Override
                    public void onDelta(String text) {
                    }

                    @Override
                    public void onDone(AskResponse r) {
                        throw new IllegalStateException("sse broken");
                    }
                });

        // R8：答案与轮次已成功提交，SSE 推送失败只记日志，不把 SUCCEEDED 改写成 FAILED
        assertEquals("答案", response.answer());
        verify(conversation).finishTaskTurn(eq("conv-1"), eq(turn), eq("BTC 多少钱"), eq("SUCCEEDED"), eq("答案"),
                anyString(), isNull(), isNull(), isNull(), eq(T0), isNull(), isNull());
        verify(conversation, never()).finishTaskTurn(eq("conv-1"), eq(turn), anyString(), eq("FAILED"),
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(traceRepository, never()).finishTrace(anyString(), eq("FAILED"), any(), anyInt(), anyInt(),
                any(), any(), any(), anyLong(), any());
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
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Consumer<CompiledPlan> sink = (Consumer<CompiledPlan>) inv.getArgument(7);
                    sink.accept(compiledPlan);
                    return new ExecutionResult("答案", evidence, 1, 1, 10, 20);
                });

        AskResponse response = service.ask("conv-1", "昨天 BTC 最高价", "v1", "Asia/Shanghai", null, null);

        var planCaptor = org.mockito.ArgumentCaptor.forClass(CompiledPlan.class);
        var scopeCaptor = org.mockito.ArgumentCaptor.forClass(com.cexpilot.conversation.TaskScope.class);
        verify(conversation).finishTaskTurn(eq("conv-1"), eq(turn), eq("昨天 BTC 最高价"), eq("SUCCEEDED"),
                eq("答案"), eq(response.traceId()), planCaptor.capture(), eq(evidence), scopeCaptor.capture(),
                eq(T0), eq(java.time.ZoneId.of("Asia/Shanghai")), isNull());
        assertEquals(compiledPlan, planCaptor.getValue());
        assertEquals(planJson, planCaptor.getValue().logicalPlanJson());
        assertEquals(com.cexpilot.conversation.TaskScope.Action.NEW, scopeCaptor.getValue().action());
    }

    @Test
    void 阶段三事件落点与拆分计数透传响应() {
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutionResult("答案", null, 2, 1, 10, 20,
                        null, null, 1, 3, 1, 2, 1, 7, List.of("fact-1")));

        AskResponse response = service.ask("conv-1", "BTC 多少钱", "v1", null, null, null);

        // 拆分计数逐字段透传到响应
        assertEquals(3, response.plannerLlmCalls());
        assertEquals(1, response.answerLlmCalls());
        assertEquals(2, response.metricFetchCount());
        assertEquals(1, response.calculationCount());
        assertEquals(7, response.httpRequestCount());
        assertEquals(1, response.reusedFactCount());

        // CONTEXT_RESOLVED 在建 trace 后、CONTEXT_COMMITTED 在轮次提交后各落一条
        var captor = org.mockito.ArgumentCaptor.forClass(com.cexpilot.runtime.TraceEvent.class);
        verify(traceSink, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        var byType = captor.getAllValues().stream()
                .filter(e -> e.eventType().startsWith("CONTEXT")).toList();
        assertEquals(List.of("CONTEXT_RESOLVED", "CONTEXT_COMMITTED"),
                byType.stream().map(com.cexpilot.runtime.TraceEvent::eventType).toList());
        var resolved = byType.get(0);
        assertTrue(resolved.outputJson().contains("conv-1"));
        assertTrue(resolved.outputJson().contains("\"query_no\":3"));
        var committed = byType.get(1);
        assertTrue(committed.outputJson().contains("fact-1"), "提交摘要带本轮新保存的 factId");
        assertTrue(committed.outputJson().contains("SUCCEEDED"));
    }

    @Test
    void 失败轮次也落CONTEXT_COMMITTED且状态为FAILED() {
        when(runtime.execute(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM 超时"));

        assertThrows(RuntimeException.class,
                () -> service.ask("conv-1", "BTC 多少钱", "v1", null, null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(com.cexpilot.runtime.TraceEvent.class);
        verify(traceSink, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        var committed = captor.getAllValues().stream()
                .filter(e -> "CONTEXT_COMMITTED".equals(e.eventType())).findFirst().orElseThrow();
        assertTrue(committed.outputJson().contains("FAILED"));
        assertTrue(committed.outputJson().contains("\"frame_updated\":false"), "失败轮次不覆盖活动 frame");
    }
}
