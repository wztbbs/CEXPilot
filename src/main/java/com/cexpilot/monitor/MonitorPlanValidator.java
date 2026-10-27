package com.cexpilot.monitor;

import com.cexpilot.agent.PlanningJson;
import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.dag.PlanNode;
import com.cexpilot.dag.PlanValidator;
import com.cexpilot.metric.*;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.time.TimeSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** 只编译、静态校验；不执行 Provider/算子，也不持久化或启用 MonitorRule。 */
@Component
public class MonitorPlanValidator {
    private static final Pattern REF=Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]{0,39})(?:\\.(binance|okx))?\\.value}}");
    private final MetricPlanCompiler compiler;
    private final PlanValidator validator;
    private final ToolRegistry registry;
    private final int maxNodes;
    public record Validated(JsonNode candidate,CompiledPlan compiled,Map<String,String> resolvedRefs) {}
    public MonitorPlanValidator(MetricCatalog catalog,ToolRegistry registry,MetricProviderRegistry providers,
                                PlanValidator validator,DagConfig dagConfig,LlmConfig llmConfig) {
        this.compiler=new MetricPlanCompiler(catalog,registry,providers); this.validator=validator; this.registry=registry;
        this.maxNodes=Math.min(dagConfig.getMaxNodes(),llmConfig.getMaxToolCalls());
    }
    public Validated validate(JsonNode envelope) {
        PlanningJson.object(envelope,Set.of("plan","monitor","needs_clarification","reply"),"monitor 规划");
        var options=envelope.path("monitor");
        PlanningJson.object(options,Set.of("action","conditions","consecutive_hits"),"monitor");
        if(!"create".equals(PlanningJson.text(options,"action",20))) throw new IllegalArgumentException("当前仅支持 monitor.action=create 的规划");
        var conditions=options.path("conditions");
        if(!conditions.isArray() || conditions.isEmpty() || conditions.size()>2) throw new IllegalArgumentException("conditions 必须为 1～2 个 AND 条件");
        if(options.has("consecutive_hits") && (!options.path("consecutive_hits").isIntegralNumber()
                || !options.path("consecutive_hits").canConvertToInt() || options.path("consecutive_hits").asInt()<1
                || options.path("consecutive_hits").asInt()>12)) throw new IllegalArgumentException("consecutive_hits 范围 1～12");
        var compiled=compiler.compileFull(envelope.path("plan"),maxNodes);
        var errors=validator.validate(compiled.dagPlan(),compiler.allowedTools(),maxNodes);
        if(!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ",errors));
        if(compiled.logicalPlan().metrics().isEmpty()) throw new IllegalArgumentException("监控必须依赖真实行情指标，不能只监控常量计算");
        String marketKey=null; java.time.Duration duration=null; String explicitInterval=null;
        for(var metric:compiled.logicalPlan().metrics()) {
            if("pinned_result".equals(metric.referenceMode())) throw new IllegalArgumentException("持续监控不能绑定历史事实快照");
            if(metric.exchanges().size()!=1) throw new IllegalArgumentException("首版监控每个指标只允许一个交易所");
            String key=metric.exchanges().get(0)+":"+metric.instrument().base();
            if(marketKey!=null && !marketKey.equals(key)) throw new IllegalArgumentException("首版 AND 条件必须使用同一交易所、同一永续品种");
            marketKey=key;
            if(metric.params() instanceof TimeQueryParams params) {
                if(!(params.time() instanceof TimeSpec.RollingWindow rolling)) throw new IllegalArgumentException("首版持续监控仅支持滚动时间窗口，不支持固定日期或自然周期");
                if(Boolean.TRUE.equals(params.includeUnclosed())) throw new IllegalArgumentException("首版监控只使用已完结数据");
                var current=rolling.duration().toDuration();
                if(current.toSeconds()%300!=0 || current.toSeconds()<300 || current.toSeconds()>86400)
                    throw new IllegalArgumentException("监控窗口为 5～1440 分钟，且必须为 5 分钟整数倍");
                if(duration!=null && !duration.equals(current)) throw new IllegalArgumentException("首版 AND 的所有时间指标必须使用相同窗口");
                duration=current;
                if(params.interval()!=null) {
                    if(explicitInterval!=null && !explicitInterval.equals(params.interval())) throw new IllegalArgumentException("首版监控的显式采样粒度必须一致");
                    explicitInterval=params.interval();
                }
            } else if(metric.shape()!=QueryShape.SNAPSHOT) throw new IllegalArgumentException("首版监控仅支持滚动窗口和快照指标");
        }
        var resolved=new LinkedHashMap<String,String>();
        var needed=new HashSet<String>();
        var seenConditions=new HashSet<String>();
        for(var condition:conditions) {
            PlanningJson.object(condition,Set.of("value_ref","operator","threshold"),"condition");
            String reference=PlanningJson.text(condition,"value_ref",100);
            String op=PlanningJson.text(condition,"operator",8);
            if(!Set.of("GT","GTE","LT","LTE").contains(op)) throw new IllegalArgumentException("比较符仅支持 GT/GTE/LT/LTE");
            JsonNode threshold=condition.get("threshold");
            if(threshold==null || !threshold.isNumber() || threshold.decimalValue().precision()>32
                    || Math.abs((long)threshold.decimalValue().scale())>16) throw new IllegalArgumentException("threshold 必须是有界十进制数，不能带单位或表达式");
            var match=REF.matcher(reference);
            if(!match.matches()) throw new IllegalArgumentException("value_ref 必须为 {{指标ID.交易所.value}} 或 {{计算ID.value}}");
            String id=match.group(1), exchange=match.group(2), physical;
            if(compiled.branches().containsKey(id)) {
                if(exchange==null || !compiled.branches().get(id).containsKey(exchange)) throw new IllegalArgumentException("value_ref 引用了不存在的指标分支");
                physical=compiled.branches().get(id).get(exchange);
            } else {
                if(exchange!=null || compiled.logicalPlan().calculations().stream().noneMatch(c->c.id().equals(id)))
                    throw new IllegalArgumentException("value_ref 引用了不存在的计算结果");
                physical=id;
            }
            PlanNode node=compiled.dagPlan().nodes().stream().filter(n->n.id().equals(physical)).findFirst().orElseThrow();
            JsonNode schema=node.metric()!=null?node.metric().outputSchema():registry.outputSchema(node.tool());
            JsonNode type=schema==null?null:schema.at("/properties/value/type");
            boolean numeric=type!=null && type.isTextual() && (Set.of("number","integer").contains(type.asText())
                    || "string".equals(type.asText()) && node.tool()!=null && registry.get(node.tool()) instanceof com.cexpilot.calculation.CalculationTool);
            if(!numeric)
                throw new IllegalArgumentException("condition 只能引用明确的数值 value，不能引用序列、布尔或动态形状");
            if(!seenConditions.add(reference+":"+op+":"+threshold.decimalValue().stripTrailingZeros().toPlainString()))
                throw new IllegalArgumentException("不能重复相同的监控条件");
            resolved.put(reference,"{{"+physical+".data.value}}");
            collectDependencies(node,compiled,needed);
        }
        if(needed.size()!=compiled.dagPlan().nodes().size()) throw new IllegalArgumentException("监控 Plan 含有未被任何条件使用的节点");
        // 每个条件必须可追溯到行情，不能在共享 Plan 内夹带独立常量告警。
        for(String value:resolved.values()) {
            String id=value.substring(2,value.indexOf(".data")); var deps=new HashSet<String>();
            collectDependencies(compiled.dagPlan().nodes().stream().filter(n->n.id().equals(id)).findFirst().orElseThrow(),compiled,deps);
            if(compiled.targets().stream().noneMatch(t->deps.contains(t.physicalId()))) throw new IllegalArgumentException("每个监控条件必须依赖行情指标");
        }
        ObjectNode candidate=((ObjectNode)envelope).deepCopy();
        ((ObjectNode)candidate.path("monitor")).put("consecutive_hits",options.path("consecutive_hits").asInt(1));
        candidate.put("needs_clarification",false); candidate.putNull("reply");
        return new Validated(candidate,compiled,Map.copyOf(resolved));
    }
    private void collectDependencies(PlanNode node,CompiledPlan compiled,Set<String> found) {
        if(!found.add(node.id())) return;
        for(String dep:node.dependsOn()) collectDependencies(compiled.dagPlan().nodes().stream()
                .filter(n->n.id().equals(dep)).findFirst().orElseThrow(),compiled,found);
    }
}
