package com.cexpilot.monitor;

import com.cexpilot.config.DagConfig;
import com.cexpilot.dag.*;
import com.cexpilot.market.Times;
import com.cexpilot.metric.*;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static com.cexpilot.monitor.MonitorModels.*;

/** 仅供已确认规则的后台执行；独立 DAG 线程池，不调用 Router/Planner/Answer。 */
@Component
public class MonitorPlanRunner {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MetricProviderRegistry providers;
    private final PlanValidator validator;
    private final MetricCatalog catalog;
    private final DagExecutor executor;
    private final Clock clock;
    public MonitorPlanRunner(MetricCatalog catalog,
                             ToolRegistry tools,MetricProviderRegistry providers) {
        this(catalog,tools,providers,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MonitorPlanRunner(MetricCatalog catalog,
                             ToolRegistry tools,MetricProviderRegistry providers,Clock clock) {
        this.providers=providers; this.catalog=catalog; this.clock=clock;
        var config=new DagConfig(); config.setExecutorThreads(2);
        this.executor=new DagExecutor(tools,config,providers);
        this.validator=new PlanValidator(tools,config);
    }
    public void validateRunnable(JsonNode definition) { loadPlan(definition); }
    private DagPlan loadPlan(JsonNode definition) {
        if(!catalog.version().equals(definition.path("catalogVersion").asText())) throw new IllegalArgumentException("指标目录已变化，请重新生成并确认规则");
        var plan=MonitorDag.read(definition.path("dag"),providers);
        var errors=validator.validate(plan,catalog.operators(),8);
        if(!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ",errors));
        MonitorDag.topological(plan);
        for(var node:plan.nodes()) {
            if(node.metric()!=null && !catalog.version().equals(node.metric().catalogVersion()))
                throw new IllegalArgumentException("固定DAG的指标目录版本不兼容，请重新创建监控");
            if(node.metricQuery() instanceof TimeRangeQuery time) {
                if(!(time.time() instanceof com.cexpilot.time.TimeSpec.RollingWindow rolling)
                        || rolling.duration().toDuration().toSeconds()%300!=0 || rolling.duration().toDuration().toSeconds()<300
                        || rolling.duration().toDuration().toSeconds()>86400 || time.includeUnclosed()
                        || time.intervalCode()!=null && !"5m".equals(time.intervalCode()))
                    throw new IllegalArgumentException("固定DAG仅支持完整的5分钟粒度滚动窗口");
            }
        }
        if(!definition.path("rules").isArray() || definition.path("rules").isEmpty()) throw new IllegalArgumentException("缺少固定监控条件");
        for(var rule:definition.path("rules")) {
            var refs=ReferenceResolver.findRefs(rule.path("resolvedValueRef"));
            if(refs.size()!=1 || plan.node(refs.get(0).nodeId())==null) throw new IllegalArgumentException("固定监控条件的物理引用无效");
        }
        return plan;
    }
    /** 单次只读调用的兼容入口；后台 Work 使用可恢复入口并显式报告失败。 */
    public Check run(JsonNode definition,Instant slot,Instant now) {
        try { return execute(definition,slot,now,null,e->{}, "monitor-check"); }
        catch(RuntimeException failure) { return new Check(Result.UNKNOWN,JSON.createObjectNode().put("reason",failure.getMessage()),"PLAN_DATA_UNAVAILABLE"); }
    }
    public Check run(MonitorWork work,Consumer<JsonNode> checkpoint) {
        try {
            return execute(work.version().definition(),work.slot(),work.startedAt(),work.execution().checkpoint(),checkpoint,
                    work.workId());
        } catch(MonitorWorkFailure failure) { throw failure; }
        catch(IllegalArgumentException invalid) { throw new MonitorWorkFailure("INVALID_DEFINITION",invalid.getMessage(),false); }
    }
    private Instant now(Instant fallback) { return clock==null?fallback:clock.instant(); }
    private Check execute(JsonNode definition,Instant slot,Instant started,JsonNode saved,Consumer<JsonNode> save,String workId) {
        var plan=loadPlan(definition);
        var checkpoint=JSON.createObjectNode().put("schemaVersion",2);
        var nodes=checkpoint.putObject("nodes");
        var reused=new HashMap<String,ToolResult>();
        // checkpoint归属于同一Execution；先固定可复用集合，再执行，不能因上游重取成功而复用旧下游。
        if(saved!=null && saved.path("schemaVersion").asInt()==2 && saved.path("nodes").isObject()) {
            for(var node:MonitorDag.topological(plan)) {
                if(!reused.keySet().containsAll(node.dependsOn())) continue;
                var cached=saved.path("nodes").path(node.id());
                if(!cached.hasNonNull("data")) continue;
                try {
                    if(node.metric()!=null) validateFresh(cached.path("data"),node,slot,now(started));
                    reused.put(node.id(),ToolResult.success(cached.path("data").deepCopy()));
                    nodes.putObject(node.id()).set("data",cached.path("data").deepCopy());
                } catch(IllegalArgumentException invalid) { /* 过期、不完整、错窗口的节点及依赖它的旧结果均不复用。 */ }
            }
        }
        var executed=executor.executeResumable(plan,workId,e->{},new RequestContext(ZoneOffset.UTC,slot),reused,(node,result)->{
            if(Thread.currentThread().isInterrupted()) throw new MonitorWorkFailure("WORK_INTERRUPTED","本次执行已中断",true);
            if(result==null || !result.ok() || result.data()==null || result.data().isNull()) return;
            for(var dep:node.dependsOn()) if(!nodes.has(dep)) return;
            if(node.metric()!=null) {
                try { validateFresh(result.data(),node,slot,now(started)); }
                catch(IllegalArgumentException incomplete) { return; } // 不保存不完整数据；独立成功节点仍可保存
            }
            nodes.putObject(node.id()).set("data",result.data().deepCopy());
            save.accept(checkpoint.deepCopy());
        });
        Instant checkedAt=now(started);
        var evidence=JSON.createObjectNode().put("data_cutoff_at",slot.toString()).put("checked_at",checkedAt.toString()).put("reused_node_count",reused.size());
        var values=evidence.putArray("values"); var facts=evidence.putArray("facts");
        for(var node:plan.nodes()) {
            var result=executed.context().get(node.id());
            if(result==null || !result.ok()) throw new MonitorWorkFailure("NODE_FAILED","指标或计算失败："+node.id(),true);
            if(node.metric()!=null) {
                try { validateFresh(result.data(),node,slot,checkedAt); }
                catch(IllegalArgumentException incomplete) { throw new MonitorWorkFailure("DATA_UNAVAILABLE",incomplete.getMessage(),true); }
            }
            facts.addObject().put("node",node.id()).set("data",result.data());
        }
        for(var rule:definition.path("rules")) {
            String ref=rule.path("resolvedValueRef").asText();
            JsonNode raw=ReferenceResolver.resolve(JSON.getNodeFactory().textNode(ref),executed.context());
            if(!(raw.isNumber() || raw.isTextual()) || !raw.asText().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                throw new MonitorWorkFailure("INVALID_VALUE","监控结果不是有限数值",false);
            BigDecimal actual=new BigDecimal(raw.asText()),threshold=rule.path("threshold").decimalValue();
            int cmp=actual.compareTo(threshold);
            boolean hit=switch(rule.path("operator").asText()) { case "GT"->cmp>0; case "GTE"->cmp>=0; case "LT"->cmp<0; case "LTE"->cmp<=0; default->throw new IllegalArgumentException("无效比较符"); };
            var value=values.addObject().put("id",rule.path("id").asText()).put("value",actual).put("matched",hit);
            value.set("threshold",rule.path("threshold")); value.put("unit",rule.path("unit").asText());
        }
        boolean match=true; for(var value:values) match &= value.path("matched").asBoolean();
        return new Check(match?Result.MATCH:Result.NO_MATCH,evidence,null);
    }
    private void validateFresh(JsonNode data,PlanNode node,Instant slot,Instant now) {
        try {
        if(data.hasNonNull("statistics_omitted") || data.has("coverage") && !data.at("/coverage/range_complete").asBoolean())
            throw new IllegalArgumentException("数据不完整");
        if(node.metricQuery() instanceof TimeRangeQuery timeQuery) {
            if(!data.at("/coverage/range_complete").asBoolean() || data.at("/coverage/contains_unclosed").asBoolean() || data.path("truncated").asBoolean())
                throw new IllegalArgumentException("数据覆盖不完整或含未收盘数据");
            var expectedRange=new com.cexpilot.time.TimeRangeResolver(Clock.fixed(slot,ZoneOffset.UTC)).resolve(ZoneOffset.UTC,timeQuery.time(),slot);
            var expectedJson=JSON.createObjectNode().put("start_inclusive",Times.readable(expectedRange.startInclusive().toEpochMilli(),expectedRange.timezone()))
                    .put("end_exclusive",Times.readable(expectedRange.endExclusive().toEpochMilli(),expectedRange.timezone())).put("timezone",expectedRange.timezone().getId());
            if(!expectedJson.equals(data.path("requested_range"))) throw new IllegalArgumentException("数据窗口与本次Execution不一致");
            var requested=data.path("requested_range");
            if(data.has("effective_range") && !requested.equals(data.path("effective_range"))) throw new IllegalArgumentException("实际窗口与本轮要求不一致");
            var zone=ZoneId.of(requested.path("timezone").asText("UTC"));
            String expected=Times.readable(slot.toEpochMilli(),zone);
            String end=data.path("sample_selection").asText().equals("boundary_values")?data.at("/selection_range/end_inclusive").asText()
                    :data.at("/source/provider").asText().equals("oi")?requested.path("end_exclusive").asText():data.at("/coverage/covered_until").asText();
            if(!expected.equals(end)) throw new IllegalArgumentException("数据没有覆盖共同截止点");
        } else {
            var time=LocalDateTime.parse(data.path("as_of").asText(),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
            if(time.isAfter(now.plusSeconds(5)) || time.isBefore(now.minusSeconds(300))) throw new IllegalArgumentException("快照已过期");
        }
        if(!now.isBefore(slot.plusSeconds(300))) throw new IllegalArgumentException("检查周期已过期");
        } catch(DateTimeException invalid) { throw new IllegalArgumentException("指标时间字段无效",invalid); }
    }
    @PreDestroy public void close() { executor.close(); }
}
