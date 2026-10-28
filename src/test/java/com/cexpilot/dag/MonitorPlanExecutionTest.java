package com.cexpilot.dag;

import com.cexpilot.config.*;
import com.cexpilot.metric.*;
import com.cexpilot.monitor.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.monitor.MonitorModels.*;

class MonitorPlanExecutionTest {
    final MetricCatalog catalog=new MetricCatalog(LOADER);
    final DagConfig dag=new DagConfig();
    final com.cexpilot.runtime.ToolRegistry tools=registry();
    final MonitorDefinitionCompiler compiler=new MonitorDefinitionCompiler(new MonitorPlanValidator(catalog,tools,catalogProviders(),new PlanValidator(tools,dag),dag,new LlmConfig()),catalog);
    final Instant slot=Instant.parse("2026-10-27T06:00:00Z");
    ObjectNode candidate() {
        var metric=metric("m1","price.change_pct","range_statistic","binance").put("interval","5m");
        metric.set("time",json("{\"type\":\"rolling_window\",\"duration\":{\"value\":1,\"unit\":\"hour\"}}"));
        var c=JSON.createObjectNode(); c.set("plan",plan(metric));
        c.putObject("monitor").put("action","create").putArray("conditions").addObject().put("value_ref","{{m1.binance.value}}").put("operator","GTE").put("threshold",1);
        return c;
    }
    @Test void autoSamplingIsFrozenAndConfirmationExpressionIncludesConstantsAndDirection() {
        var c=candidate(); ((ObjectNode)c.at("/plan/metrics/0")).remove("interval");
        calculation((ObjectNode)c.path("plan"),"c1","difference","{\"left\":100,\"right\":\"{{m1.binance.value}}\"}");
        ((ObjectNode)c.at("/monitor/conditions/0")).put("value_ref","{{c1.value}}");
        var definition=compiler.compile(c,"Asia/Shanghai");
        assertEquals("5m",definition.at("/candidate/plan/metrics/0/interval").asText());
        var description=definition.at("/rules/0/description").asText();
        assertTrue(description.contains("left=100"),description); assertTrue(description.contains("right=Binance"),description);
        assertEquals("percentage_point",definition.at("/rules/0/unit").asText());
        assertEquals("5m",definition.at("/dag/nodes/0/args/interval").asText());
        assertEquals("metric_0",definition.at("/dag/nodes/0/id").asText());
        assertTrue(definition.at("/rules/0/resolvedValueRef").asText().contains("c1"));
    }
    @Test void storedPhysicalDagExecutesWithoutTheCandidateAndKeepsRollingTimeBoundToEachExecution() throws Exception {
        var c=candidate(); calculation((ObjectNode)c.path("plan"),"c1","difference","{\"left\":\"{{m1.binance.value}}\",\"right\":1}");
        ((ObjectNode)c.at("/monitor/conditions/0")).put("value_ref","{{c1.value}}");
        ObjectNode definition=(ObjectNode)JSON.readTree(compiler.compile(c,"UTC").toString());
        definition.remove("candidate"); // 确认运行不再解析候选计划，也不重新决定节点id或默认参数。
        var nodes=(ArrayNode)definition.at("/dag/nodes"); var first=nodes.remove(0); nodes.add(first); // 拓扑恢复不依赖JSON排列。
        var fixed=definition.path("dag").deepCopy(); var cutoffs=new java.util.ArrayList<Instant>();
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,providers(provider((q,ctx)->{
            assertEquals("5m",((TimeRangeQuery)q).intervalCode()); assertEquals("CHANGE_PCT",q.binding().selector().name());
            cutoffs.add(ctx.requestTime()); return metricResult(q,ctx,12,true);
        }))))) {
            assertEquals(Result.MATCH,r.value.run(definition,slot,slot.plusSeconds(20)).result());
            assertEquals(Result.MATCH,r.value.run(definition,slot.plusSeconds(300),slot.plusSeconds(320)).result());
        }
        assertEquals(java.util.List.of(slot,slot.plusSeconds(300)),cutoffs); assertEquals(fixed,definition.path("dag"));
    }
    @Test void legacyOrUnknownDagFormatsAndIncompatibleCatalogAreRejectedBeforeMarketCalls() {
        var definition=compiler.compile(candidate(),"UTC"); var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,providers(provider((q,ctx)->{
            calls.incrementAndGet(); return metricResult(q,ctx,12,true);
        }))))) {
            var legacy=definition.deepCopy(); legacy.remove("dag");
            assertTrue(assertThrows(IllegalArgumentException.class,()->r.value.validateRunnable(legacy)).getMessage().contains("重新创建"));
            var future=definition.deepCopy(); ((ObjectNode)future.path("dag")).put("schemaVersion",99);
            assertThrows(IllegalArgumentException.class,()->r.value.validateRunnable(future));
            definition.put("catalogVersion","old"); assertThrows(IllegalArgumentException.class,()->r.value.validateRunnable(definition));
            assertEquals(0,calls.get());
        }
    }
    @Test void andConditionsUseOneCompiledDagAndPreserveEvidenceWithDecimalCalculations() {
        var c=candidate();
        calculation((ObjectNode)c.path("plan"),"c1","difference","{\"left\":\"{{m1.binance.value}}\",\"right\":1}");
        ((ArrayNode)c.at("/monitor/conditions")).addObject().put("value_ref","{{c1.value}}").put("operator","GT").put("threshold",5);
        var definition=compiler.compile(c,"Asia/Shanghai");
        try(Runner runner=new Runner(new MonitorPlanRunner(catalog,tools,providers(provider((q,ctx)->metricResult(q,ctx,12,true)))))) {
            var result=runner.value.run(definition,slot,slot.plusSeconds(20));
            assertEquals(Result.MATCH,result.result(),result.evidence().toString());
            assertEquals(2,result.evidence().path("values").size()); assertEquals(2,result.evidence().path("facts").size());
            ((ObjectNode)definition.at("/rules/1")).put("threshold",100);
            assertEquals(Result.NO_MATCH,runner.value.run(definition,slot,slot.plusSeconds(20)).result());
        }
    }
    @Test void missingPartialStaleDataIsUnknownInsteadOfNoMatch() {
        var definition=compiler.compile(candidate(),"UTC");
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,providers(provider((q,ctx)->metricResult(q,ctx,11,false)))))) {
            var result=r.value.run(definition,slot,slot.plusSeconds(20));
            assertEquals(Result.UNKNOWN,result.result()); assertEquals("PLAN_DATA_UNAVAILABLE",result.errorCode());
        }
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,providers()))) {
            assertEquals(Result.UNKNOWN,r.value.run(definition,slot,slot.plusSeconds(301)).result());
            definition.put("catalogVersion","old");
            assertThrows(IllegalArgumentException.class,()->r.value.validateRunnable(definition));
        }
    }
    @Test void priceAndOiShareExactCutoffAndMissingOiEndpointMakesWholeCheckUnknown() {
        var c=candidate();
        var oi=((ObjectNode)c.at("/plan/metrics/0")).deepCopy().put("id","m2").put("metric","oi.change_pct");
        ((ArrayNode)c.at("/plan/metrics")).add(oi);
        ((ArrayNode)c.at("/monitor/conditions")).addObject().put("value_ref","{{m2.binance.value}}").put("operator","GT").put("threshold",3);
        var definition=compiler.compile(c,"Asia/Shanghai");
        var complete=new java.util.concurrent.atomic.AtomicBoolean(true);
        var oiProvider=namedProvider(OiMetricProvider.NAME,OiMetric::valueOf,(q,ctx)->{
            assertEquals(slot,ctx.requestTime());
            var range=new com.cexpilot.time.TimeRange(slot.minusSeconds(3600),slot,ZoneOffset.UTC);
            var coverage=new com.cexpilot.market.series.SeriesCoverage(13,complete.get()?13:12,complete.get()?java.util.List.of():java.util.List.of(slot.toEpochMilli()),
                    java.util.List.of(),java.util.List.of(),false,false,null,slot.toEpochMilli(),false);
            var metadata=new MetricResult.SeriesMetadata(range,range,coverage,"5m",false,complete.get()?13:12);
            if(!complete.get()) return new MetricResult.Omitted(metadata,"缺少终点");
            return new MetricResult.SampledScalar(metadata,new java.math.BigDecimal("5"),range.startInclusive(),slot);
        });
        var providers=new MetricProviderRegistry(java.util.List.of(provider((q,ctx)->metricResult(q,ctx,12,true)),oiProvider));
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,providers))) {
            var check=r.value.run(definition,slot,slot.plusSeconds(20));
            assertEquals(Result.MATCH,check.result(),check.evidence().toString());
            assertEquals(slot.toString(),check.evidence().at("/facts/1/data/end_sample_at").asText());
            complete.set(false);
            assertEquals(Result.UNKNOWN,r.value.run(definition,slot,slot.plusSeconds(20)).result());
        }
    }
    @Test void snapshotFreshnessUsesCompletionClockAndRejectsOldObservations() {
        var c=candidate(); var metric=(ObjectNode)c.at("/plan/metrics/0");
        metric.put("metric","oi.quantity").put("query_shape","snapshot").remove(java.util.List.of("time","interval"));
        var definition=compiler.compile(c,"UTC");
        var observed=new java.util.concurrent.atomic.AtomicReference<>(slot.plusSeconds(30));
        var providers=new MetricProviderRegistry(java.util.List.of(namedProvider(OiMetricProvider.NAME,OiMetric::valueOf,(q,ctx)->
                new MetricResult.Point(new MetricResult.SnapshotMetadata(observed.get(),ZoneOffset.UTC),java.math.BigDecimal.TEN))));
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,providers,Clock.fixed(slot.plusSeconds(40),ZoneOffset.UTC)))) {
            assertEquals(Result.MATCH,r.value.run(definition,slot,slot.plusSeconds(20)).result());
            observed.set(slot.minusSeconds(301));
            assertEquals(Result.UNKNOWN,r.value.run(definition,slot,slot.plusSeconds(20)).result());
        }
    }
    @Test void explicitIncompatibleGrainCannotBeEnabled() {
        var c=candidate(); ((ObjectNode)c.at("/plan/metrics/0")).put("interval","15m");
        var definition=compiler.compile(c,"UTC");
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,providers()))) {
            assertThrows(IllegalArgumentException.class,()->r.value.validateRunnable(definition));
        }
    }
    @Test void networkFailureIsReportedDuringExecutionRatherThanPlanValidation() {
        var definition=compiler.compile(candidate(),"UTC");
        var failing=providers(provider((q,ctx)->{ throw new IllegalStateException("network unavailable"); }));
        try(Runner r=new Runner(new MonitorPlanRunner(catalog,tools,failing))) {
            assertDoesNotThrow(()->r.value.validateRunnable(definition));
            var check=r.value.run(definition,slot,slot.plusSeconds(20));
            assertEquals(Result.UNKNOWN,check.result()); assertEquals("PLAN_DATA_UNAVAILABLE",check.errorCode());
        }
    }
    record Runner(MonitorPlanRunner value) implements AutoCloseable { public void close() { value.close(); } }
}
