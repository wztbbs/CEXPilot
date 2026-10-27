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
    private final MonitorDefinitionCompiler compiler;
    private final MetricCatalog catalog;
    private final DagExecutor executor;
    private final Clock clock;
    public MonitorPlanRunner(MonitorDefinitionCompiler compiler,MetricCatalog catalog,
                             ToolRegistry tools,MetricProviderRegistry providers) {
        this(compiler,catalog,tools,providers,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MonitorPlanRunner(MonitorDefinitionCompiler compiler,MetricCatalog catalog,
                             ToolRegistry tools,MetricProviderRegistry providers,Clock clock) {
        this.compiler=compiler; this.catalog=catalog; this.clock=clock;
        var config=new DagConfig(); config.setExecutorThreads(2);
        this.executor=new DagExecutor(tools,config,providers);
    }
    public void validateRunnable(JsonNode definition) {
        if(!catalog.version().equals(definition.path("catalogVersion").asText())) throw new IllegalArgumentException("指标目录已变化，请重新生成并确认规则");
        var checked=compiler.validate(definition.path("candidate"));
        for(var metric:checked.compiled().logicalPlan().metrics()) {
            if(metric.params() instanceof TimeQueryParams time && time.interval()!=null && !"5m".equals(time.interval()))
                throw new IllegalArgumentException("当前后台检查固定5分钟，仅可确认5m或自动采样的时间指标；请调整候选粒度");
        }
    }
    /** 单次只读调用的兼容入口；后台 Work 使用可恢复入口并显式报告失败。 */
    public Check run(JsonNode definition,Instant slot,Instant now) {
        try { return execute(definition,slot,now,null,e->{},"", "monitor-check"); }
        catch(RuntimeException failure) { return new Check(Result.UNKNOWN,JSON.createObjectNode().put("reason",failure.getMessage()),"PLAN_DATA_UNAVAILABLE"); }
    }
    public Check run(MonitorWork work,Consumer<JsonNode> checkpoint) {
        try {
            return execute(work.version().definition(),work.slot(),work.startedAt(),work.execution().checkpoint(),checkpoint,
                    work.version().specHash(),work.workId());
        } catch(MonitorWorkFailure failure) { throw failure; }
        catch(IllegalArgumentException invalid) { throw new MonitorWorkFailure("INVALID_DEFINITION",invalid.getMessage(),false); }
    }
    private Instant now(Instant fallback) { return clock==null?fallback:clock.instant(); }
    private Check execute(JsonNode definition,Instant slot,Instant started,JsonNode saved,Consumer<JsonNode> save,String specHash,String workId) {
        validateRunnable(definition);
        var validated=compiler.validate(definition.path("candidate"));
        var plan=validated.compiled().dagPlan();
        var checkpoint=JSON.createObjectNode().put("schemaVersion",1).put("specHash",specHash).put("slot",slot.toString())
                .put("catalogVersion",catalog.version());
        var nodes=checkpoint.putObject("nodes");
        var reused=new HashMap<String,ToolResult>();
        boolean same=saved!=null && saved.path("schemaVersion").asInt()==1 && saved.path("specHash").asText().equals(specHash)
                && saved.path("slot").asText().equals(slot.toString()) && saved.path("catalogVersion").asText().equals(catalog.version());
        // 只有全部依赖仍被复用，计算结果才可复用；失效快照会连带使下游计算失效。
        if(same) {
            boolean progress;
            do {
                progress=false;
                for(var node:plan.nodes()) {
                    if(reused.containsKey(node.id()) || !reused.keySet().containsAll(node.dependsOn())) continue;
                    var cached=saved.path("nodes").path(node.id());
                    if(!signature(node).equals(cached.path("signature").asText()) || !cached.hasNonNull("data")) continue;
                    try {
                        if(node.metric()!=null) validateFresh(cached.path("data"),slot,now(started));
                        reused.put(node.id(),ToolResult.success(cached.path("data").deepCopy())); nodes.set(node.id(),cached.deepCopy()); progress=true;
                    } catch(IllegalArgumentException stale) { /* 重新取数；不会复用依赖它的计算。 */ }
                }
            } while(progress);
        }
        var executed=executor.executeResumable(plan,workId,e->{},new RequestContext(ZoneOffset.UTC,slot),reused,(node,result)->{
            if(Thread.currentThread().isInterrupted()) throw new MonitorWorkFailure("WORK_INTERRUPTED","本次执行已中断",true);
            if(result==null || !result.ok()) return;
            if(node.metric()!=null) {
                try { validateFresh(result.data(),slot,now(started)); }
                catch(IllegalArgumentException incomplete) { return; } // 不保存不完整数据；独立成功节点仍可保存
            }
            nodes.putObject(node.id()).put("signature",signature(node)).set("data",result.data().deepCopy());
            save.accept(checkpoint.deepCopy());
        });
        Instant checkedAt=now(started);
        var evidence=JSON.createObjectNode().put("data_cutoff_at",slot.toString()).put("checked_at",checkedAt.toString()).put("reused_node_count",reused.size());
        var values=evidence.putArray("values"); var facts=evidence.putArray("facts");
        for(var node:plan.nodes()) {
            var result=executed.context().get(node.id());
            if(result==null || !result.ok()) throw new MonitorWorkFailure("NODE_FAILED","指标或计算失败："+node.id(),true);
            if(node.metric()!=null) {
                try { validateFresh(result.data(),slot,checkedAt); }
                catch(IllegalArgumentException incomplete) { throw new MonitorWorkFailure("DATA_UNAVAILABLE",incomplete.getMessage(),true); }
            }
            facts.addObject().put("node",node.id()).set("data",result.data());
        }
        for(var rule:definition.path("rules")) {
            String ref=validated.resolvedRefs().get(rule.path("valueRef").asText());
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
    private String signature(PlanNode node) {
        return MonitorJson.hash(String.valueOf(node.tool())+":"+node.args()+":"+node.dependsOn()+":"+(node.metric()==null?"":node.metric().identity()));
    }
    private void validateFresh(JsonNode data,Instant slot,Instant now) {
        if(data.hasNonNull("statistics_omitted") || data.has("coverage") && !data.at("/coverage/range_complete").asBoolean())
            throw new IllegalArgumentException("数据不完整");
        if(data.has("requested_range")) {
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
        if(now.isAfter(slot.plusSeconds(300))) throw new IllegalArgumentException("检查周期已过期");
    }
    @PreDestroy public void close() { executor.close(); }
}
