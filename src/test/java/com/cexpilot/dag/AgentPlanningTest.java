package com.cexpilot.dag;

import com.cexpilot.agent.*;
import com.cexpilot.config.*;
import com.cexpilot.conversation.PlannerInput;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.llm.*;
import com.cexpilot.metric.*;
import com.cexpilot.monitor.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPlanningTest {
    private static final RequestContext REQUEST=new RequestContext(ZoneOffset.UTC,Instant.parse("2026-10-27T06:00:20Z"));
    private ObjectNode candidate() {
        var p=plan(metric("m1","price.change_pct","range_statistic","binance"));
        ((ObjectNode)p.at("/metrics/0")).set("time",json("{\"type\":\"rolling_window\",\"duration\":{\"value\":1,\"unit\":\"hour\"}}"));
        ((ObjectNode)p.at("/metrics/0")).put("interval","5m");
        var result=JSON.createObjectNode(); result.set("plan",p);
        result.putObject("monitor").put("action","create").putArray("conditions").addObject()
                .put("value_ref","{{m1.binance.value}}").put("operator","LT").put("threshold",-2);
        result.put("needs_clarification",false).putNull("reply"); return result;
    }
    private MonitorPlanValidator validator() {
        var registry=registry(); var config=new DagConfig();
        return new MonitorPlanValidator(new MetricCatalog(LOADER),registry,catalogProviders(),new PlanValidator(registry,config),config,new LlmConfig());
    }
    private MonitorPlanner monitor(LlmClient client) {
        var store=new PromptStore(LOADER); var tools=registry();
        return new MonitorPlanner(new PlanningLlm(client,new LlmConfig(),store),store,new MetricCatalog(LOADER),tools,validator(),new DagConfig());
    }
    private IntentRouter router(LlmClient client) {
        var store=new PromptStore(LOADER);
        return new IntentRouter(new PlanningLlm(client,new LlmConfig(),store),store);
    }
    @Test void routingPromptOnlyClassifiesAndReceivesTaskContext() {
        var client=new Script("{\"route\":\"monitor\"}");
        var state=JSON.createObjectNode().put("current_agent","monitor");
        var decision=router(client).route("改成3%",state,"t",e->{});
        assertEquals("monitor",decision.route()); assertEquals(1,decision.usages().size());
        assertTrue(client.calls.get(0).get(1).content().contains("current_agent"));
        assertFalse(client.calls.get(0).get(0).content().contains("price.change_pct"));
    }
    @Test void invalidRouteRepairsOnceThenClarifiesWithoutDefaultingToQueryOrMonitor() {
        var client=new Script("{\"route\":\"trade\"}","not json"); var events=new ArrayList<TraceEvent>();
        var result=router(client).route("它呢",JSON.createObjectNode(),"t",events::add);
        assertEquals("clarify",result.route()); assertEquals(2,result.usages().size());
        assertEquals(2,events.stream().filter(e->e.eventType().equals("LLM_CALL")).count());
        assertTrue(events.get(0).inputJson().contains("messages"));
    }
    @Test void routerTransportFailureRetainsUnknownUsageAndFullPrompt() {
        LlmClient broken=(messages,tools)->{ throw new IllegalStateException("timeout"); };
        var events=new ArrayList<TraceEvent>();
        var error=assertThrows(IntentRouter.Failure.class,()->router(broken).route("监控BTC",JSON.createObjectNode(),"t",events::add));
        assertEquals(1,error.usages.size()); assertNull(LlmUsage.totalCost(error.usages));
        assertEquals("timeout",events.get(0).error()); assertTrue(events.get(0).inputJson().contains("监控BTC"));
    }
    @Test void validSingleAndAndPlansCompileWithoutFetchingOrCreatingTasks() {
        var p=candidate(); var accepted=validator().validate(p);
        assertEquals(1,accepted.candidate().at("/monitor/consecutive_hits").asInt());
        assertEquals("{{metric_0.data.value}}",accepted.resolvedRefs().get("{{m1.binance.value}}"));
        var oi=((ObjectNode)p.at("/plan/metrics/0")).deepCopy().put("id","m2").put("metric","oi.change_pct");
        ((com.fasterxml.jackson.databind.node.ArrayNode)p.at("/plan/metrics")).add(oi);
        ((com.fasterxml.jackson.databind.node.ArrayNode)p.at("/monitor/conditions")).addObject()
                .put("value_ref","{{m2.binance.value}}").put("operator","GT").put("threshold",3);
        assertEquals(2,validator().validate(p).compiled().dagPlan().nodes().size());
    }
    @Test void twoBoundsOnSameValueAndComputedDecimalValueAreSupported() {
        var p=candidate();
        ((com.fasterxml.jackson.databind.node.ArrayNode)p.at("/monitor/conditions")).addObject()
                .put("value_ref","{{m1.binance.value}}").put("operator","GT").put("threshold",-5);
        assertEquals(1,validator().validate(p).resolvedRefs().size());
        var avg=candidate(); ((ObjectNode)avg.at("/plan/metrics/0")).put("metric","price.close").put("query_shape","time_series");
        calculation((ObjectNode)avg.path("plan"),"c1","avg","{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        ((ObjectNode)avg.at("/monitor/conditions/0")).put("value_ref","{{c1.value}}").put("threshold",100);
        assertEquals("{{c1.data.value}}",validator().validate(avg).resolvedRefs().get("{{c1.value}}"));
    }
    @ParameterizedTest @ValueSource(strings={"{{m1.value}}","{{m1.okx.value}}","{{m1.binance.samples}}","{{c_missing.value}}","{{m1.binance.value}} + 1"})
    void referencesMustResolveToOneDeclaredScalar(String ref) {
        var p=candidate(); ((ObjectNode)p.at("/monitor/conditions/0")).put("value_ref",ref);
        assertThrows(IllegalArgumentException.class,()->validator().validate(p));
    }
    @Test void sequenceCannotBeDirectlyComparedAndUnusedBranchesAreRejected() {
        var p=candidate(); ((ObjectNode)p.at("/plan/metrics/0")).put("metric","price.close").put("query_shape","time_series");
        assertThrows(IllegalArgumentException.class,()->validator().validate(p));
        var extra=candidate(); ((com.fasterxml.jackson.databind.node.ArrayNode)extra.at("/plan/metrics"))
                .add(((ObjectNode)extra.at("/plan/metrics/0")).deepCopy().put("id","unused"));
        assertThrows(IllegalArgumentException.class,()->validator().validate(extra));
    }
    @Test void historyFixedTimeCrossExchangeAndMismatchedWindowsAreRejected() {
        var pinned=candidate(); ((ObjectNode)pinned.at("/plan/metrics/0")).put("reference_mode","pinned_result").put("evidence_ref","fact_1");
        assertThrows(IllegalArgumentException.class,()->validator().validate(pinned));
        var calendar=candidate(); ((ObjectNode)calendar.at("/plan/metrics/0")).set("time",json(DAY));
        assertThrows(IllegalArgumentException.class,()->validator().validate(calendar));
        for(String field:List.of("exchange","window")) {
            var p=candidate(); var second=((ObjectNode)p.at("/plan/metrics/0")).deepCopy().put("id","m2");
            if(field.equals("exchange")) second.set("exchanges",JSON.createArrayNode().add("okx"));
            else ((ObjectNode)second.at("/time/duration")).put("value",2);
            ((com.fasterxml.jackson.databind.node.ArrayNode)p.at("/plan/metrics")).add(second);
            assertThrows(IllegalArgumentException.class,()->validator().validate(p));
        }
    }
    @Test void invalidThresholdHitsOrRelationCannotPassValidation() {
        var p=candidate(); ((ObjectNode)p.at("/monitor/conditions/0")).put("threshold","2%");
        assertThrows(IllegalArgumentException.class,()->validator().validate(p));
        var hits=candidate(); ((ObjectNode)hits.path("monitor")).put("consecutive_hits",0);
        assertThrows(IllegalArgumentException.class,()->validator().validate(hits));
        var or=candidate(); ((ObjectNode)or.path("monitor")).put("relation","OR");
        assertThrows(IllegalArgumentException.class,()->validator().validate(or));
        var empty=candidate(); ((com.fasterxml.jackson.databind.node.ArrayNode)empty.at("/monitor/conditions")).removeAll();
        assertThrows(IllegalArgumentException.class,()->validator().validate(empty));
    }
    @Test void monitorPlannerRepairsInvalidRefAndNeverCallsQueryPlannerOrAnswer() {
        var bad=candidate(); ((ObjectNode)bad.at("/monitor/conditions/0")).put("value_ref","{{missing.value}}");
        var script=new Script(bad.toString(),candidate().toString()); var events=new ArrayList<TraceEvent>();
        var outcome=monitor(script).plan("监控BTC跌2%",JSON.createObjectNode(),"t",events::add);
        assertEquals("PLANNED",outcome.status()); assertEquals(2,outcome.usages().size());
        assertNotNull(outcome.candidate()); assertEquals(2,script.calls.size());
        assertTrue(script.calls.get(0).get(0).content().contains("独立的持续行情监控规划器"));
        assertEquals(2,events.stream().filter(e->e.eventType().equals("MONITOR_PLAN")).count());
    }
    @Test void monitorThresholdRetainsExactDecimalAndDuplicateRoutingKeysRequireRepair() {
        String raw=candidate().toString().replace("\"threshold\":-2","\"threshold\":2.0000000000000001");
        var planned=monitor(new Script(raw)).plan("监控BTC",JSON.createObjectNode(),"t",e->{});
        assertEquals(new java.math.BigDecimal("2.0000000000000001"),planned.candidate().at("/monitor/conditions/0/threshold").decimalValue());
        var script=new Script("{\"route\":\"query\",\"route\":\"monitor\"}","{\"route\":\"query\"}");
        var route=router(script).route("查询BTC",JSON.createObjectNode(),"t",e->{});
        assertEquals("query",route.route()); assertEquals(2,route.usages().size());
    }
    @Test void clarifyAndUnsupportedDoNotReturnAnExecutableCandidate() {
        var malicious=candidate().put("needs_clarification",true).put("reply","需要什么阈值？");
        assertNull(monitor(new Script(malicious.toString())).plan("监控BTC",JSON.createObjectNode(),"t",e->{}).candidate());
        var unsupported=monitor(new Script("{\"plan\":null,\"monitor\":null,\"reply\":\"暂停功能尚未接入\"}"))
                .plan("暂停它",JSON.createObjectNode(),"t",e->{});
        assertEquals("UNSUPPORTED",unsupported.status()); assertNull(unsupported.candidate());
    }
    @Test void dispatcherRoutesQueryThroughOriginalRuntimeAndAccountsRouterSeparately() {
        var script=new Script("{\"route\":\"query\"}"); var query=mock(DagRuntime.class);
        when(query.execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new ExecutionResult("行情",JSON.createArrayNode(),1,1,10,5)
                        .withLlmUsages(List.of(new LlmUsage("normal","fake",10,5,0.0))));
        var dispatcher=new AgentDispatcher(router(script),query,monitor(script),new PromptStore(LOADER));
        var result=dispatch(dispatcher,"BTC现价",JSON.createObjectNode());
        assertEquals("query",result.agentType()); assertEquals(1,result.routerLlmCalls()); assertEquals(20,result.promptTokens());
        assertEquals(1,script.calls.size()); verify(query).execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void dispatcherMonitorOnlyPlansAndRouterDoesNotSeeFullMonitorPlan() {
        var script=new Script("{\"route\":\"monitor\"}",candidate().toString()); var query=mock(DagRuntime.class);
        var dispatcher=new AgentDispatcher(router(script),query,monitor(script),new PromptStore(LOADER));
        var state=JSON.createObjectNode().put("active_agent","monitor");
        state.putObject("monitor_context").set("candidate",candidate());
        var result=dispatch(dispatcher,"改成3%",state);
        assertEquals("monitor",result.agentType()); assertEquals("PLANNED",result.agentPayload().path("status").asText());
        assertEquals("NOT_CREATED",result.agentPayload().path("execution_status").asText());
        assertEquals(1,result.routerLlmCalls()); assertEquals(1,result.plannerLlmCalls()); assertEquals(0,result.answerLlmCalls());
        assertEquals(0,result.toolCallCount()); assertNull(result.taskScope()); verifyNoInteractions(query);
        assertFalse(script.calls.get(0).get(1).content().contains("\"metrics\""));
        assertTrue(script.calls.get(1).get(1).content().contains("candidate"));
    }
    @Test void productionMonitorBranchPersistsDraftAndReturnsConfirmationInsteadOfExecutingQuery() {
        var script=new Script("{\"route\":\"monitor\"}",candidate().toString());
        var query=mock(DagRuntime.class); var flow=mock(MonitorConversationFlow.class);
        when(flow.enrichState(anyString(),any())).thenAnswer(inv->inv.getArgument(1));
        var card=JSON.createObjectNode().put("ruleId","draft-1").put("version",1).put("requiredHits",2);
        card.set("candidate",candidate()); card.putArray("rules").addObject().put("description","BTC跌2%");
        when(flow.saveDraft(any(),any(),any(),any(),any())).thenReturn(card);
        var dispatcher=new AgentDispatcher(router(script),query,monitor(script),new PromptStore(LOADER),flow);
        var result=dispatcher.execute("监控BTC","","t",e->{},null,REQUEST,null,null,new PlannerInput("",null),
                new EvidenceSession("c","turn"),JSON.createObjectNode(),agent->{},"visitor");
        assertEquals("PENDING_CONFIRMATION",result.agentPayload().path("status").asText());
        assertEquals("NOT_ENABLED",result.agentPayload().path("execution_status").asText());
        assertEquals("draft-1",result.agentPayload().at("/confirmation/ruleId").asText());
        assertTrue(result.answer().contains("BTC跌2%")); assertEquals(0,result.toolCallCount());
        verify(flow).saveDraft(eq("visitor"),eq(new EvidenceSession("c","turn")),eq("监控BTC"),any(),eq("Z"));
        verifyNoInteractions(query);
    }
    @Test void ambiguousAndTransportFailurePathsNeverExecuteAQuery() {
        var script=new Script("{\"route\":\"clarify\",\"question\":\"查询还是监控？\"}"); var query=mock(DagRuntime.class);
        var dispatcher=new AgentDispatcher(router(script),query,monitor(script),new PromptStore(LOADER));
        assertEquals("NEEDS_CLARIFICATION",dispatch(dispatcher,"它呢",JSON.createObjectNode()).turnStatus()); verifyNoInteractions(query);
        LlmClient failed=(m,t)->{ throw new IllegalStateException("offline"); };
        var broken=new AgentDispatcher(router(failed),query,monitor(failed),new PromptStore(LOADER));
        var error=assertThrows(DagRuntime.ExecutionFailure.class,()->dispatch(broken,"BTC",JSON.createObjectNode()));
        assertEquals(1,error.counters.routerLlmCalls()); assertEquals(0,error.counters.plannerLlmCalls());
    }
    @Test void realQueryBranchStillUsesOldPlannerDagAndAnswerWithAnAdditionalRouterCall() {
        var raw=candidate().path("plan");
        var script=new Script("{\"route\":\"query\"}",envelope(raw),"BTC最近一小时变化率为12%。");
        var tools=registry(); var config=new DagConfig(); var store=new PromptStore(LOADER);
        var executor=new DagExecutor(tools,config,providers(provider((q,c)->metricResult(q,c,12,true))));
        try {
            var query=new DagRuntime(script,planner(script,tools,config),executor,store,Clock.fixed(REQUEST.requestTime(),ZoneOffset.UTC));
            var dispatcher=new AgentDispatcher(router(script),query,monitor(script),store);
            var events=new ArrayList<TraceEvent>();
            var result=dispatcher.execute("BTC最近一小时变化率", "", "t",events::add,null,REQUEST,null,p->{},new PlannerInput("",null),
                    new EvidenceSession("c","turn"),JSON.createObjectNode(),r->{});
            assertEquals("query",result.agentType()); assertEquals(1,result.routerLlmCalls());
            assertEquals(1,result.plannerLlmCalls()); assertEquals(1,result.answerLlmCalls()); assertEquals(1,result.metricFetchCount());
            assertEquals(3,script.calls.size()); assertEquals(30,result.promptTokens()); assertEquals(3,result.llmUsages().size());
            assertEquals(3,events.stream().filter(e->e.eventType().equals("LLM_CALL")).count());
            assertTrue(script.calls.get(1).get(0).content().contains("指标查询规划模块"));
        } finally { executor.shutdown(); }
    }
    @Test void failedQueryRetainsRouterAndQueryCostsWithoutChangingStageCounts() {
        var script=new Script("{\"route\":\"query\"}"); var query=mock(DagRuntime.class);
        var original=new DagRuntime.TurnCounters(1,1,1,0,1,0,List.of()).withLlmUsages(List.of(
                new LlmUsage("normal","fake",10,5,0.0),new LlmUsage("normal","fake",null,null,null)));
        when(query.execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenThrow(new DagRuntime.ExecutionFailure(new IllegalStateException("answer timeout"),original));
        var dispatcher=new AgentDispatcher(router(script),query,monitor(script),new PromptStore(LOADER));
        var failure=assertThrows(DagRuntime.ExecutionFailure.class,()->dispatch(dispatcher,"BTC",JSON.createObjectNode()));
        assertEquals(1,failure.counters.routerLlmCalls()); assertEquals(1,failure.counters.plannerLlmCalls());
        assertEquals(1,failure.counters.answerLlmCalls()); assertEquals(3,failure.counters.llmUsages().size());
        assertNull(LlmUsage.totalCost(failure.counters.llmUsages()));
    }
    @Test void queryAfterMonitorUsesMonitorDefinitionForReferenceAndDoesNotContinueOldQueryByDefault() {
        var script=new Script("{\"route\":\"query\"}"); var query=mock(DagRuntime.class);
        when(query.execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(new ExecutionResult("现价",null,0,0,0,0));
        var dispatcher=new AgentDispatcher(router(script),query,monitor(script),new PromptStore(LOADER));
        var state=JSON.createObjectNode().put("active_agent","monitor"); state.putObject("monitor_context").set("candidate",candidate());
        dispatch(dispatcher,"监控那个币现在多少钱",state);
        var captured=org.mockito.ArgumentCaptor.forClass(PlannerInput.class);
        verify(query).execute(any(),any(),any(),any(),any(),any(),any(),any(),captured.capture(),any());
        assertNull(captured.getValue().currentTask()); assertNull(captured.getValue().activeFrame());
        assertTrue(captured.getValue().contextText().contains("跨 Agent 查询"));
        assertTrue(captured.getValue().contextText().contains("BTC"));
    }
    private ExecutionResult dispatch(AgentDispatcher dispatcher,String question,JsonNode state) {
        return dispatcher.execute(question,"历史对话","t",e->{},null,REQUEST,null,p->{fail("监控规划不应作为查询执行计划回调");},
                new PlannerInput("",null),new EvidenceSession("c","turn"),state,r->{});
    }
}
