package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.*;
import java.util.UUID;
import static com.cexpilot.monitor.MonitorModels.*;

/** 真 SQL/事务测试；只连接独立 H2 内存库，不访问用户数据库、交易所或邮件服务。 */
class MonitorTestFixture implements AutoCloseable {
    static final Instant START=Instant.parse("2026-10-26T06:00:20Z");
    final MutableClock clock=new MutableClock(START);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final MonitorJson json=new MonitorJson(mapper);
    final MonitorConfig config=new MonitorConfig();
    final JdbcTemplate jdbc;
    final MonitorRepository repository;
    final MonitorExecutionService executions;
    final MonitorTriggerService trigger=new MonitorTriggerService();
    MonitorTestFixture() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:monitor_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);
        config.setMailFrom("monitor@example.com");
        repository=new MonitorRepository(jdbc,new DataSourceTransactionManager(source),json,config,clock);
        executions=new MonitorExecutionService(jdbc,repository,config,json,clock,trigger);
    }
    // 低层执行测试直接准备已编译定义，不保留已删除的HTTP命令入口。
    record Detail(Rule rule,Version version,com.fasterxml.jackson.databind.JsonNode latestEvaluation) {}
    static com.fasterxml.jackson.databind.node.ObjectNode candidate(int hits) {
        var node=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var plan=node.putObject("plan"); var metric=plan.putArray("metrics").addObject();
        metric.put("id","m1").put("metric","price.change_pct").put("query_shape","range_statistic").put("interval","5m");
        metric.putArray("exchanges").add("binance");
        metric.putObject("instrument").put("market_type","perpetual").put("base","BTC").put("quote","USDT");
        metric.putObject("time").put("type","rolling_window").putObject("duration").put("value",1).put("unit","hour");
        plan.putArray("calculations");
        node.putObject("monitor").put("action","create").put("consecutive_hits",hits).putArray("conditions")
                .addObject().put("value_ref","{{m1.binance.value}}").put("operator","LT").put("threshold",-2);
        return node;
    }
    Detail draft(int hits) {
        String id=UUID.randomUUID().toString();
        var definition=mapper.createObjectNode().put("schemaVersion",2).put("timezone","Asia/Shanghai")
                .put("requiredHits",hits).put("catalogVersion","fixture").put("relation","AND");
        definition.set("candidate",candidate(hits));
        definition.putArray("rules").addObject().put("id","condition_1").put("valueRef","{{m1.binance.value}}")
                .put("operator","LT").put("threshold",-2).put("unit","percent").put("description","BTC最近60分钟价格变化率小于-2%");
        repository.transaction(()->{
            jdbc.update("INSERT INTO monitor_rule (rule_id,owner_id,request_id,status,created_at,updated_at) VALUES (?,'v:fixture',?,'PENDING_CONFIRMATION',?,?)",
                    id,id,MonitorRepository.utc(clock.instant()),MonitorRepository.utc(clock.instant()));
            jdbc.update("INSERT INTO monitor_rule_version (rule_id,version,user_query,rule_spec_json,spec_hash,status,created_at) VALUES (?,1,'监控BTC',?,?,'PENDING',?)",
                    id,json.write(definition),MonitorJson.hash(json.write(definition)),MonitorRepository.utc(clock.instant()));
            return null;
        });
        return detail(id);
    }
    Detail active(int hits) { var draft=draft(hits); confirm(draft.rule().ruleId()); return detail(draft.rule().ruleId()); }
    void confirm(String id) {
        repository.transaction(()->{
            jdbc.update("UPDATE monitor_rule SET recipient='pilot@example.com' WHERE rule_id=?",id);
            repository.activate(id,1,clock.instant()); return null;
        });
    }
    Detail detail(String id) {
        var rule=repository.findAny(id,false);
        return new Detail(rule,repository.version(id,rule.activeVersion()==null?rule.pendingVersion():rule.activeVersion()),repository.latestEvaluation(id));
    }
    void stop(String id) { repository.transaction(()->{ repository.stop(id,clock.instant()); return null; }); }
    Check check(Result result) {
        var evidence=mapper.createObjectNode().put("value","-3").put("data_cutoff_at",clock.instant().minusSeconds(20).toString());
        evidence.putArray("values").addObject().put("id","condition_1").put("value","-3").put("unit","percent").put("matched",result==Result.MATCH);
        return new Check(result,evidence,result==Result.UNKNOWN?"TEST_MISSING":null);
    }
    MonitorWork claim(String id) {
        executions.materialize(id);
        for(var e:executions.list(id)) { var work=executions.claim(e.executionId()); if(work!=null) return work; }
        return null;
    }
    void run(MonitorWorkManager manager,String id) {
        executions.materialize(id); for(var e:executions.list(id)) manager.runNow(e.executionId());
    }
    void evaluate(String id,Result result) {
        var claim=claim(id);
        if(claim==null) throw new AssertionError("没有领取到检查任务");
        if(!executions.complete(claim,check(result))) throw new AssertionError("提交失败");
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    @Override public void close() { jdbc.execute("SHUTDOWN"); }
    static class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now=now; }
        void advance(long seconds) { now=now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        @Override public Instant instant() { return now; }
    }
}
