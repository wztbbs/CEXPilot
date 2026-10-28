package com.cexpilot.dag;

import com.cexpilot.config.*;
import com.cexpilot.metric.*;
import com.cexpilot.monitor.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.cexpilot.dag.MetricTestSupport.*;
import static com.cexpilot.monitor.MonitorModels.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitorCheckpointTest {
    final Instant slot=Instant.parse("2026-10-27T06:00:00Z");
    final MetricCatalog catalog=new MetricCatalog(LOADER);
    final DagConfig dag=new DagConfig();
    final com.cexpilot.runtime.ToolRegistry tools=registry();
    final MonitorDefinitionCompiler compiler=new MonitorDefinitionCompiler(new MonitorPlanValidator(catalog,tools,catalogProviders(),new PlanValidator(tools,dag),dag,new LlmConfig()),catalog);
    final AtomicReference<Instant> current=new AtomicReference<>(slot.plusSeconds(20));
    final Clock clock=new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        public Instant instant() { return current.get(); }
    };
    ObjectNode rolling(String id,String metric) {
        var m=metric(id,metric,"range_statistic","binance").put("interval","5m");
        m.set("time",json("{\"type\":\"rolling_window\",\"duration\":{\"value\":1,\"unit\":\"hour\"}}")); return m;
    }
    ObjectNode envelope(ObjectNode plan,String first,String second) {
        var c=JSON.createObjectNode(); c.set("plan",plan);
        var rules=c.putObject("monitor").put("action","create").putArray("conditions");
        rules.addObject().put("value_ref",first).put("operator","GT").put("threshold",1);
        rules.addObject().put("value_ref",second).put("operator","GT").put("threshold",1); return c;
    }
    MonitorWork work(JsonNode definition,JsonNode checkpoint,int attempt) {
        String hash=MonitorJson.hash(definition.toString());
        var rule=new Rule("rule","owner","request","ACTIVE",1,current.get(),null,0,"ARMED",JSON.createArrayNode(),current.get(),current.get());
        var version=new Version("rule",1,"question",hash,"APPROVED",slot,slot,definition);
        var execution=new MonitorExecution("execution","rule",1,hash,slot,slot.plusSeconds(300),MonitorExecution.State.RUNNING,attempt,attempt,
                current.get().plusSeconds(60),checkpoint,null,null,slot,null);
        return new MonitorWork("work-"+attempt,execution,new Claim(rule,version,slot),current.get());
    }
    @Test void retryReusesCompletedMetricAndCalculationWhileFetchingOnlyFailedOi() {
        var p=plan(rolling("m1","price.change_pct"),rolling("m2","oi.change_pct"));
        calculation(p,"c1","difference","{\"left\":\"{{m1.binance.value}}\",\"right\":1}");
        var definition=compiler.compile(envelope(p,"{{c1.value}}","{{m2.binance.value}}"),"UTC");
        var priceCalls=new AtomicInteger(); var oiCalls=new AtomicInteger();
        var oi=namedProvider(OiMetricProvider.NAME,OiMetric::valueOf,(q,c)->{
            if(oiCalls.incrementAndGet()==1) throw new IllegalStateException("temporary network error");
            var range=new com.cexpilot.time.TimeRange(slot.minusSeconds(3600),slot,ZoneOffset.UTC);
            var coverage=new com.cexpilot.market.series.SeriesCoverage(13,13,List.of(),List.of(),List.of(),false,false,null,slot.toEpochMilli(),false);
            return new MetricResult.SampledScalar(new MetricResult.SeriesMetadata(range,range,coverage,"5m",false,13),new BigDecimal("5"),range.startInclusive(),slot);
        });
        var providers=new MetricProviderRegistry(List.of(provider((q,c)->{ priceCalls.incrementAndGet(); return metricResult(q,c,12,true); }),oi));
        var saved=new AtomicReference<JsonNode>();
        try(var runner=new Runner(new MonitorPlanRunner(compiler,catalog,tools,providers,clock))) {
            assertThrows(MonitorWorkFailure.class,()->runner.value.run(work(definition,null,1),saved::set));
            assertTrue(saved.get().path("nodes").has("metric_0")); assertTrue(saved.get().path("nodes").has("c1"));
            assertFalse(saved.get().path("nodes").has("metric_1"));
            var check=runner.value.run(work(definition,saved.get(),2),saved::set);
            assertEquals(Result.MATCH,check.result()); assertEquals(2,check.evidence().path("reused_node_count").asInt());
            assertEquals(1,priceCalls.get()); assertEquals(2,oiCalls.get());
            ((ObjectNode)saved.get()).put("specHash","wrong-version");
            var fresh=runner.value.run(work(definition,saved.get(),3),saved::set);
            assertEquals(0,fresh.evidence().path("reused_node_count").asInt()); assertEquals(2,priceCalls.get());
        }
    }
    @Test void staleSnapshotInvalidatesDependentCalculationAndIsFetchedAgain() {
        var snapshot=metric("m1","oi.quantity","snapshot","binance"); snapshot.remove(List.of("time","interval"));
        var p=plan(snapshot,rolling("m2","price.change_pct"));
        calculation(p,"c1","difference","{\"left\":\"{{m1.binance.value}}\",\"right\":1}");
        var definition=compiler.compile(envelope(p,"{{c1.value}}","{{m2.binance.value}}"),"UTC");
        var oiCalls=new AtomicInteger(); var priceCalls=new AtomicInteger();
        var oi=namedProvider(OiMetricProvider.NAME,OiMetric::valueOf,(q,c)->{
            int count=oiCalls.incrementAndGet();
            return new MetricResult.Point(new MetricResult.SnapshotMetadata(count==1?slot.minusSeconds(250):current.get(),ZoneOffset.UTC),BigDecimal.valueOf(count==1?10:20));
        });
        var providers=new MetricProviderRegistry(List.of(oi,provider((q,c)->{
            if(priceCalls.incrementAndGet()==1) throw new IllegalStateException("transient"); return metricResult(q,c,12,true);
        })));
        var saved=new AtomicReference<JsonNode>();
        try(var runner=new Runner(new MonitorPlanRunner(compiler,catalog,tools,providers,clock))) {
            assertThrows(MonitorWorkFailure.class,()->runner.value.run(work(definition,null,1),saved::set));
            assertTrue(saved.get().path("nodes").has("c1"));
            current.set(slot.plusSeconds(80));
            var check=runner.value.run(work(definition,saved.get(),2),saved::set);
            assertEquals(0,check.evidence().path("reused_node_count").asInt()); assertEquals(2,oiCalls.get());
            assertEquals(new BigDecimal("19"),check.evidence().at("/values/0/value").decimalValue().stripTrailingZeros());
        }
    }
    @Test void realManagerAndSqlResumeCheckpointsIntoOneEvaluationAndOneOutbox() throws Exception {
        var p=plan(rolling("m1","price.change_pct"),rolling("m2","oi.change_pct"));
        calculation(p,"c1","difference","{\"left\":\"{{m1.binance.value}}\",\"right\":1}");
        var definition=compiler.compile(envelope(p,"{{c1.value}}","{{m2.binance.value}}"),"UTC");
        var priceCalls=new AtomicInteger(); var oiCalls=new AtomicInteger();
        var providers=new MetricProviderRegistry(List.of(provider((q,c)->{ priceCalls.incrementAndGet(); return metricResult(q,c,12,true); }),
                namedProvider(OiMetricProvider.NAME,OiMetric::valueOf,(q,c)->{
                    if(oiCalls.incrementAndGet()==1) throw new IllegalStateException("retry OI");
                    var range=new com.cexpilot.time.TimeRange(slot.minusSeconds(3600),slot,ZoneOffset.UTC);
                    var coverage=new com.cexpilot.market.series.SeriesCoverage(13,13,List.of(),List.of(),List.of(),false,false,null,slot.toEpochMilli(),false);
                    return new MetricResult.SampledScalar(new MetricResult.SeriesMetadata(range,range,coverage,"5m",false,13),BigDecimal.TEN,range.startInclusive(),slot);
                })));
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:checkpoint_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/schema.sql")).execute(source);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source); var config=new MonitorConfig();
        config.setMailFrom("support@hrscrm.net");
        var json=new MonitorJson(JSON); var repository=new MonitorRepository(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),json,config,clock);
        var utc=MonitorRepository.utc(current.get()); String hash=MonitorJson.hash(definition.toString());
        jdbc.update("INSERT INTO monitor_rule (rule_id,owner_id,request_id,status,active_version,recipient,next_check_at,created_at,updated_at) VALUES ('rule','owner','request','ACTIVE',1,'friend@example.com',?,?,?)",utc,utc,utc);
        jdbc.update("INSERT INTO monitor_rule_version (rule_id,version,rule_spec_json,spec_hash,status,created_at) VALUES ('rule',1,?,?,'APPROVED',?)",json.write(definition),hash,utc);
        var executions=new MonitorExecutionService(jdbc,repository,config,json,clock,new MonitorTriggerService());
        var pool=new MonitorExecutors();
        try(var runner=new Runner(new MonitorPlanRunner(compiler,catalog,tools,providers,clock))) {
            var worker=new MonitorWorker(runner.value);
            var manager=new MonitorWorkManager(executions,worker,pool,clock);
            assertEquals(1,executions.materialize("rule")); manager.dispatch();
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while(executions.list("rule").get(0).status()!=MonitorExecution.State.DONE && System.nanoTime()<end) Thread.sleep(5);
            var execution=executions.list("rule").get(0);
            assertEquals(MonitorExecution.State.DONE,execution.status()); assertEquals(2,execution.attemptCount());
            assertEquals(1,priceCalls.get()); assertEquals(2,oiCalls.get()); assertEquals(3,execution.checkpoint().path("nodes").size());
            assertEquals(2,repository.latestEvaluation("rule").at("/evidence/reused_node_count").asInt());
            for(String table:List.of("monitor_evaluation","monitor_event","notification_delivery"))
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        } finally { pool.shutdown(); jdbc.execute("SHUTDOWN"); }
    }
    record Runner(MonitorPlanRunner value) implements AutoCloseable { public void close() { value.close(); } }
}
