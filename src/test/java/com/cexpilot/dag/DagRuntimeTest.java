package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.metric.*;
import com.cexpilot.market.Times;
import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.cexpilot.time.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 固定模型响应 + 真实编译器/调度器/算子，覆盖新主流程、时间上下文和流式返回。 */
class DagRuntimeTest {
    private final List<DagExecutor> executors=new ArrayList<>();
    @AfterEach void close() { executors.forEach(DagExecutor::shutdown); }
    private DagRuntime runtime(Script llm,ToolRegistry registry,DagConfig config,Clock clock) {
        return runtime(llm,registry,config,clock,provider((q,c)->metricResult(q,c,24,true)));
    }
    private DagRuntime runtime(Script llm,ToolRegistry registry,DagConfig config,Clock clock,MetricProvider provider) {
        var executor=new DagExecutor(registry,config,providers(provider));executors.add(executor);
        return new DagRuntime(llm,planner(llm,registry,config),executor,new PromptStore(LOADER),clock);
    }
    private DagRuntime runtime(Script llm,ToolRegistry registry) {
        return runtime(llm,registry,new DagConfig(),Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"),ZoneOffset.UTC));
    }
    private DagRuntime runtime(Script llm,ToolRegistry registry,MetricProvider provider) {
        return runtime(llm,registry,new DagConfig(),Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"),ZoneOffset.UTC),provider);
    }
    private static JsonNode result(ExecutionResult r,String id) {
        for (JsonNode node:r.evidence()) if(id.equals(node.path("node_id").asText())) return node;
        throw new AssertionError("missing "+id);
    }
    @Test void groupedQueryCalculatesAndAnswerReceivesStandardFactsWithDirection() {
        var p=plan(metric("m1","trade.turnover","range_statistic","binance","okx"));
        calculation(p,"c1","relative_change","{\"current\":\"{{m1.binance.value}}\",\"baseline\":\"{{m1.okx.value}}\"}");
        var llm=new Script(envelope(p),"币安比OKX高20%。");var trace=new ArrayList<TraceEvent>();
        var r=runtime(llm,registry()).execute("比较两所成交额，并解释差异原因","","t",trace::add);
        assertEquals("币安比OKX高20%。",r.answer());assertEquals(3,r.toolCallCount());assertEquals(2,r.steps());
        assertEquals(20,r.promptTokens());assertEquals(10,r.completionTokens());
        assertEquals("20",result(r,"c1").at("/data/percent").asText());
        assertEquals("USDT",result(r,"metric_0").at("/data/unit").asText());
        assertFalse(result(r,"metric_0").path("data").has("statistics"));
        assertEquals(2,result(r,"c1").at("/data/metric_sources").size());
        assertEquals("{{metric_0.data.value}}",result(r,"c1").at("/input_references/input/current").asText());
        assertEquals(2,trace.stream().filter(e->e.eventType().equals("METRIC_RESULT")).count());
        assertEquals(1,trace.stream().filter(e->e.eventType().equals("TOOL_CALL")).count());
        assertTrue(trace.stream().filter(e->e.eventType().equals("METRIC_RESULT")).allMatch(e->e.durationMs()!=null));
        assertTrue(result(r,"metric_0").at("/data/source/provider").asText().equals("kline"));
        String answer=llm.calls.get(1).get(1).content();
        assertFalse(answer.contains("\"statistics\""));assertTrue(answer.contains("trade.turnover"));
        String system = llm.calls.get(1).get(0).content();
        assertTrue(system.contains("事实性结论只能来自 FACTS"));
        assertTrue(system.contains("涉及跨所比较时，两侧必须是同一指标定义、可比单位和同一时间窗口"));
        assertTrue(system.contains("标明依据的指标，并区分观测与推断"));
        assertTrue(system.contains("只有单侧数据不能下跨所结论"));
        assertTrue(system.contains("即使两侧数值都已返回，也不能冒充已有比较结果"));
        assertFalse(system.contains("intent_guidance"));
        assertFalse(system.contains("本轮问题归类为"));
    }
    @Test void sequenceAverageAndMultiLayerCalculationKeepProvenance() {
        var p=plan(metric("m1","price.close","time_series","binance","okx"));
        calculation(p,"a","avg","{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        calculation(p,"b","avg","{\"kind\":\"field\",\"collection\":\"{{m1.okx.samples}}\",\"field\":\"value\"}");
        calculation(p,"c","difference","{\"left\":\"{{a.value}}\",\"right\":\"{{b.value}}\"}");
        var r=runtime(new Script(envelope(p),"均值相同"),registry()).execute("两个均值差","","t",e->{});
        assertEquals(3,r.steps());assertEquals(5,r.toolCallCount());
        assertEquals("112.5",result(r,"a").at("/data/value").asText());
        assertEquals("0",result(r,"c").at("/data/value").asText());
        assertEquals("USDT",result(r,"c").at("/data/unit").asText());
        assertEquals(2,result(r,"c").at("/data/metric_sources").size());
    }
    @Test void allSeriesRowsReachAnswerAndMinPreservesTime() {
        var p=plan(metric("m1","price.close","time_series","binance"));
        calculation(p,"minimum","min","{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        var llm=new Script(envelope(p),"最低101");
        var r=runtime(llm,registry(),provider((q,c)->metricResult(q,c,72,true))).execute("完整序列与最小值","","t",e->{});
        assertEquals(72,result(r,"metric_0").at("/data/samples").size());
        assertEquals(result(r,"metric_0").at("/data/samples/0/time").asText(),result(r,"minimum").at("/data/item/time").asText());
        assertTrue(llm.calls.get(1).get(1).content().contains(result(r,"metric_0").at("/data/samples/71/time").asText()));
    }
    @Test void incompleteMetricCannotFeedCalculationsOrDownstreamCalculations() {
        var p=scalarPlan();
        calculation(p,"a","avg","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\",100]}");
        calculation(p,"b","difference","{\"left\":\"{{a.value}}\",\"right\":1}");
        var llm=new Script(envelope(p),"覆盖不足，不能计算");
        var r=runtime(llm,registry(),provider((q,c)->metricResult(q,c,24,false))).execute("均值","","t",e->{});
        assertTrue(result(r,"metric_0").path("ok").asBoolean());
        assertFalse(result(r,"metric_0").path("data").has("value"));
        assertFalse(result(r,"a").path("ok").asBoolean());
        assertTrue(result(r,"b").path("error").asText().contains("skipped"));
    }
    @Test void oneExchangeFailureDoesNotEraseOtherBranch() {
        var p=plan(metric("m1","trade.turnover","range_statistic","binance","okx"));
        calculation(p,"c","compare","{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m1.okx.value}}\"}");
        var r=runtime(new Script(envelope(p),"只有币安结果"),registry(),provider((q,c)->{
            if(((com.cexpilot.metric.TimeRangeQuery)q).exchange()==com.cexpilot.market.Exchange.OKX)throw new IllegalArgumentException("upstream timeout");
            return metricResult(q,c,24,true);
        }))
                .execute("比较","","t",e->{});
        assertTrue(result(r,"metric_0").path("ok").asBoolean());assertFalse(result(r,"metric_1").path("ok").asBoolean());
        assertEquals("okx",result(r,"metric_1").at("/identity/exchange").asText());
        assertFalse(result(r,"c").path("ok").asBoolean());
    }
    @Test void providerFailureBecomesFailedMetricEvidence() {
        var r=runtime(new Script(envelope(scalarPlan()),"来源缺失"),registry(),provider((q,c)->{
            throw new IllegalArgumentException("来源指标不是数值或缺失");
        })).execute("成交额","","t",e->{});
        assertFalse(result(r,"metric_0").path("ok").asBoolean());
        assertTrue(result(r,"metric_0").path("error").asText().contains("指标查询失败"));
    }
    @Test void incompatibleUnitsFailButTwoExchangesOfSameUnitCanCompare() {
        var m2=metric("m2","trade.volume","range_statistic","okx");
        ((ObjectNode)m2.get("instrument")).put("base","ETH");
        var p=plan(metric("m1","trade.volume","range_statistic","binance"),m2);
        calculation(p,"c","sum","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\",\"{{m2.okx.value}}\"]}");
        var r=runtime(new Script(envelope(p),"单位不同"),registry()).execute("合计","","t",e->{});
        assertFalse(result(r,"c").path("ok").asBoolean());assertTrue(result(r,"c").path("error").asText().contains("单位不一致"));
    }
    @Test void unsupportedAndOutOfDomainRepliesAndExhaustedRepairsSkipExecutionAndAnswer() {
        for(String envelope:List.of("{\"in_domain\":false,\"plan\":null,\"reply\":\"仅支持加密货币\"}",
                "{\"in_domain\":true,\"plan\":null,\"reply\":\"资金费率暂不支持\"}",
                "{\"in_domain\":true,\"plan\":{\"metrics\":[],\"calculations\":[]},\"reply\":\"请提供币种\"}")) {
            var llm=new Script(envelope);var deltas=new ArrayList<String>();var trace=new ArrayList<TraceEvent>();
            var r=runtime(llm,registry()).execute("问题","","t",trace::add,deltas::add);
            assertEquals(0,r.toolCallCount());assertTrue(r.evidence().isEmpty());assertEquals(1,llm.calls.size());
            assertEquals(List.of(r.answer()),deltas);assertFalse(trace.stream().anyMatch(e->e.eventType().equals("TOOL_CALL")));
        }
        var config=new DagConfig();config.setPlannerMaxRetries(1);var llm=new Script("bad","bad","不应调用");
        var r=runtime(llm,registry(),config,Clock.systemUTC()).execute("问题","","t",e->{});
        assertTrue(r.answer().contains("本次未执行查询"));assertEquals(2,llm.calls.size());assertEquals(0,r.toolCallCount());
    }
    @Test void partialSupportedQuestionKeepsGapAndFacts() {
        ObjectNode e=(ObjectNode)json(envelope(scalarPlan()));e.put("reply","资金费率暂不支持");
        var llm=new Script(e.toString(),"成交额120，费率暂不支持");
        runtime(llm,registry()).execute("成交额与资金费率","","t",x->{});
        assertTrue(llm.calls.get(1).get(1).content().contains("资金费率暂不支持"));
        assertTrue(llm.calls.get(1).get(1).content().contains("<QUERY_STATUS>"));
    }
    @Test void streamingAnswerAndFallbackBothWork() {
        for(boolean streaming:List.of(true,false)) {
            Script llm=streaming?new Script(envelope(scalarPlan())) {
                @Override public ChatResponse chatStream(List<ChatMessage> messages,List<ToolSpec> tools,Consumer<String> onDelta) {
                    calls.add(List.copyOf(messages));onDelta.accept("最终");onDelta.accept("回答");
                    return new ChatResponse("最终回答",List.of(),10,5);
                }
            }:new Script(envelope(scalarPlan()),"最终回答");
            var deltas=new ArrayList<String>();
            var r=runtime(llm,registry()).execute("成交额","","t",e->{},deltas::add);
            assertEquals("最终回答",r.answer());assertEquals(streaming?List.of("最终","回答"):List.of("最终回答"),deltas);
            assertEquals(2,llm.calls.size());
        }
    }
    private static JsonNode timeContext(Script llm) {
        String system=llm.calls.get(1).get(0).content();
        return json(system.substring(system.indexOf("<TIME_CONTEXT>")+14,system.indexOf("</TIME_CONTEXT>")));
    }
    @Test void explicitTimezoneAndRequestInstantSurvivePlannerAndAdapter() {
        Instant request=Instant.parse("2026-09-24T16:30:00Z");
        Clock clock=Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"),ZoneId.of("Europe/London"));
        for(String zone:List.of("Asia/Shanghai","America/New_York")) {
            for(boolean explicit:List.of(true,false)) {
                var p=scalarPlan();((ObjectNode)p.at("/metrics/0/time")).put("segment","afternoon");
                if(explicit)((ObjectNode)p.at("/metrics/0/time")).put("timezone","America/New_York");
                var ctxRef=new AtomicReference<RequestContext>();
                var source=provider((q,c)->{ctxRef.set(c);return metricResult(q,c,24,true);});
                var llm=new Script(envelope(p),"回答");
                var r=runtime(llm,registry(),new DagConfig(),clock,source).execute("昨天下午","","t",e->{},null,new RequestContext(ZoneId.of(zone),request));
                assertEquals(request,ctxRef.get().requestTime());assertEquals(zone,ctxRef.get().userZone().getId());
                assertEquals(request.toString(),timeContext(llm).path("request_time_utc").asText());
                assertEquals(zone,timeContext(llm).path("timezone").asText());
                assertEquals(explicit?"America/New_York":zone,r.evidence().get(0).at("/data/requested_range/timezone").asText());
                Instant expected=Instant.parse(explicit||zone.equals("America/New_York")?"2026-09-23T16:00:00Z":"2026-09-24T04:00:00Z");
                ZoneId effectiveZone=ZoneId.of(explicit?"America/New_York":zone);
                assertEquals(Times.readable(expected.toEpochMilli(),effectiveZone),r.evidence().get(0).at("/data/requested_range/start_inclusive").asText());
            }
        }
    }
    @Test void defaultRequestInstantIsFrozenAcrossMidnightAndSharedAcrossExchanges() {
        Clock clock=mock(Clock.class);Instant start=Instant.parse("2026-09-25T15:59:59Z");when(clock.instant()).thenReturn(start);
        var p=plan(metric("m1","trade.turnover","range_statistic","binance","okx"));
        var llm=new Script(envelope(p),"回答") {
            @Override public ChatResponse chat(List<ChatMessage> messages,List<ToolSpec> tools) {
                if(calls.isEmpty()){verify(clock,times(1)).instant();when(clock.instant()).thenReturn(start.plusSeconds(2));}
                return super.chat(messages,tools);
            }
        };
        var seen=Collections.synchronizedList(new ArrayList<RequestContext>());
        var source=provider((q,c)->{seen.add(c);return metricResult(q,c,24,true);});
        runtime(llm,registry(),new DagConfig(),clock,source).execute("昨天成交额","","t",e->{},s->{});
        assertEquals(2,seen.size());seen.forEach(c->assertEquals(start,c.requestTime()));
        assertEquals("2026-09-25",timeContext(llm).path("current_date").asText());
        assertEquals("default",timeContext(llm).path("timezone_source").asText());verify(clock,times(1)).instant();
    }

    @Test void priceAnnualizationRequiresSameSourceSecondsAndRejectsDerivedRates() {
        for (String period : List.of(
                "{\"value\":86400,\"unit\":\"second\"}",
                "{\"value\":\"{{m1.binance.observation_seconds}}\",\"unit\":\"hour\"}",
                "{\"value\":\"{{m2.okx.observation_seconds}}\",\"unit\":\"second\"}")) {
            var p = plan(metric("m1", "price.change_pct", "range_statistic", "binance"),
                    metric("m2", "price.change_pct", "range_statistic", "okx"));
            calculation(p, "c", "annualize", "{\"basis\":\"holding_return\",\"method\":\"simple\","
                    + "\"rate\":\"{{m1.binance.value}}\",\"rate_unit\":\"percent\",\"period\":" + period + "}");
            var r = runtime(new Script(envelope(p), "缺少合法周期"), registry()).execute("年化", "", "t", e -> {});
            assertFalse(result(r, "c").path("ok").asBoolean(), period);
            assertTrue(result(r, "c").path("error").asText().contains("同一结果"));
        }
        var p = plan(metric("m1", "price.change_pct", "range_statistic", "binance"));
        calculation(p, "a", "avg", "{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");
        calculation(p, "c", "annualize", """
                {"basis":"holding_return","method":"simple","rate":"{{a.value}}",
                 "rate_unit":"percent","period":{"value":1,"unit":"day"}}
                """);
        var r = runtime(new Script(envelope(p), "不可年化"), registry()).execute("年化", "", "t", e -> {});
        assertTrue(result(r, "a").path("ok").asBoolean());
        assertFalse(result(r, "c").path("ok").asBoolean());
        assertTrue(result(r, "c").path("error").asText().contains("中间计算结果"));
    }
    @Test void explicitConstantRateAndPeriodStillAnnualize() {
        var p = plan();
        calculation(p, "c", "annualize", """
                {"basis":"periodic_rate","method":"simple","rate":0.0001,
                 "rate_unit":"ratio","period":{"value":8,"unit":"hour"}}
                """);
        var r = runtime(new Script(envelope(p), "10.95%"), registry()).execute("每8小时0.01%，简单年化", "", "t", e -> {});
        assertTrue(result(r, "c").path("ok").asBoolean());
        assertEquals("10.95", result(r, "c").at("/data/percent").asText());
    }

    // ===== 阶段二：REUSE_FACT 全链路（服务端绑定 → 播种 → Answer 标注 → 不重复入库） =====

    private static ObjectNode snapshotGroup(String id, String exchange, String evidenceRef) {
        ObjectNode group = JSON.createObjectNode().put("id", id).put("metric", "price.last")
                .put("query_shape", "snapshot");
        group.putArray("exchanges").add(exchange);
        group.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        if (evidenceRef != null) {
            group.put("reference_mode", "pinned_result").put("evidence_ref", evidenceRef);
        }
        return group;
    }

    private com.cexpilot.evidence.EvidenceStore seededStore(String conversationId) throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
        var target = compiler.compileFull(plan(snapshotGroup("m1", "binance", null)), 8).targets().get(0);
        ObjectNode payload = JSON.createObjectNode()
                .put("group_id", "m1").put("metric", "price.last").put("exchange", "binance")
                .put("query_shape", "snapshot").put("unit", "USDT")
                .put("catalog_version", target.catalogVersion())
                .put("as_of", "2026-10-12 09:00:00").put("value", 83000)
                .put("estimated", false).put("truncated", false);
        payload.set("instrument", target.instrument());
        payload.putObject("source").put("provider", target.provider()).put("selector", "LAST");
        store.saveFact(conversationId, "turn-1", "trace-1", target, payload,
                Instant.parse("2026-10-12T01:00:00Z"));
        return store;
    }

    private DagRuntime runtimeWithStore(Script llm, ToolRegistry registry, MetricProvider provider,
                                        com.cexpilot.evidence.EvidenceStore store) {
        var config = new DagConfig();
        var executor = new DagExecutor(registry, config, providers(provider));
        executors.add(executor);
        return new DagRuntime(llm, planner(llm, registry, config), executor, new PromptStore(LOADER),
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC), store);
    }

    @Test void reusedFactSeedsAnswerWithoutProviderAndIsNotResaved() throws Exception {
        var store = seededStore("conv-1");
        var entries = com.cexpilot.evidence.EvidenceAssembler.assignRefs(
                store.listReusable("conv-1", Instant.parse("2026-10-12T01:30:00Z")));
        var plannerInput = new com.cexpilot.conversation.PlannerInput("", null, entries);
        var llm = new Script(envelope(plan(snapshotGroup("m1", "binance", "fact_1"))), "83000（昨日快照）");
        var trace = new ArrayList<TraceEvent>();
        // Provider 一旦调用即失败：证明 REUSE 不取数
        var runtime = runtimeWithStore(llm, registry(), provider((q, c) -> {
            throw new IllegalStateException("REUSE 轮次不得取数");
        }), store);

        var r = runtime.execute("刚才那个价格用了多少？", "", "t", trace::add, null,
                new RequestContext(ZoneId.of("Asia/Shanghai"), Instant.parse("2026-10-12T02:00:00Z")),
                null, null, plannerInput, new com.cexpilot.evidence.EvidenceSession("conv-1", "turn-2"));

        assertEquals("83000（昨日快照）", r.answer());
        assertEquals(1, r.reusedFactCount());
        JsonNode entry = result(r, "metric_0");
        assertTrue(entry.path("reused").asBoolean());
        assertEquals("turn-1", entry.path("source_turn_id").asText());
        assertEquals("2026-10-12 09:00:00", entry.path("observed_at").asText());
        assertEquals(83000, entry.at("/data/value").asDouble());
        assertTrue(entry.has("fact_id"));
        assertTrue(trace.stream().anyMatch(e -> e.eventType().equals("FACT_REUSED")));
        assertTrue(trace.stream().anyMatch(e -> e.eventType().equals("EVIDENCE_REUSE_DECISION")));
        assertTrue(trace.stream().noneMatch(e -> e.eventType().equals("METRIC_RESULT")));
        // 复用节点不重复入库；Answer 看到的 FACTS 带复用标注
        assertEquals(1, store.listReusable("conv-1", Instant.parse("2026-10-12T03:00:00Z")).size());
        assertTrue(llm.calls.get(1).get(1).content().contains("\"reused\":true"));
    }

    @Test void freshFetchIsSavedAsFactBeforeAnswer() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var plannerInput = new com.cexpilot.conversation.PlannerInput("", null, List.of());
        var llm = new Script(envelope(plan(snapshotGroup("m1", "binance", null))), "最新价 90000");
        var ticker = namedProvider(TickerMetricProvider.NAME, TickerMetric::valueOf, (q, c) ->
                new MetricResult.Point(new MetricResult.SnapshotMetadata(
                        Instant.parse("2026-10-12T02:00:00Z"), ZoneId.of("Asia/Shanghai")),
                        new java.math.BigDecimal("90000")));
        var runtime = runtimeWithStore(llm, registry(), ticker, store);

        var r = runtime.execute("BTC 现价", "", "t", e -> {}, null,
                new RequestContext(ZoneId.of("Asia/Shanghai"), Instant.parse("2026-10-12T02:00:00Z")),
                null, null, plannerInput, new com.cexpilot.evidence.EvidenceSession("conv-1", "turn-9"));

        assertEquals(0, r.reusedFactCount());
        JsonNode entry = result(r, "metric_0");
        assertTrue(entry.has("fact_id"), "本轮新取事实应落库并标注 fact_id");
        assertFalse(entry.has("reused"));
        var facts = store.listReusable("conv-1", Instant.parse("2026-10-12T02:00:01Z"));
        assertEquals(1, facts.size());
        assertEquals("turn-9", facts.get(0).sourceTurnId());
        assertEquals("t", facts.get(0).sourceTraceId());
    }

}
