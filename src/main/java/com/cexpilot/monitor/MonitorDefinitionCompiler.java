package com.cexpilot.monitor;

import com.cexpilot.dag.PlanNode;
import com.cexpilot.dag.ReferenceResolver;
import com.cexpilot.metric.*;
import com.cexpilot.time.TimeSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** 候选 Plan → 不可变执行定义 + 面向用户的规则清单；不调用 LLM，不取行情。 */
@Component
public class MonitorDefinitionCompiler {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MonitorPlanValidator validator;
    private final MetricCatalog catalog;
    public MonitorDefinitionCompiler(MonitorPlanValidator validator,MetricCatalog catalog) { this.validator=validator; this.catalog=catalog; }
    public ObjectNode compile(JsonNode candidate,String timezone) {
        var checked=validator.validate(candidate);
        // 自动粒度固定为5m，随确认内容持久化，避免后台按窗口自动切换粒度。
        ObjectNode normalized=checked.candidate().deepCopy();
        for(var metric:normalized.path("plan").path("metrics")) {
            var binding=catalog.metricDefinition(metric.path("metric").asText()).bindings(metric.path("instrument").path("market_type").asText()).get(QueryShape.from(metric.path("query_shape").asText()));
            if(binding.hasIntervalSupport() && !metric.hasNonNull("interval")) ((ObjectNode)metric).put("interval","5m");
        }
        checked=validator.validate(normalized);
        var result=JSON.createObjectNode().put("schemaVersion",2).put("timezone",java.time.ZoneId.of(timezone).getId())
                .put("catalogVersion",catalog.version()).put("requiredHits",checked.candidate().at("/monitor/consecutive_hits").asInt());
        result.set("candidate",checked.candidate());
        result.set("dag",MonitorDag.write(checked.compiled().dagPlan()));
        var rules=result.putArray("rules");
        int index=0;
        for(var condition:checked.candidate().at("/monitor/conditions")) {
            String ref=condition.path("value_ref").asText();
            String physical=ReferenceResolver.findRefs(JSON.getNodeFactory().textNode(checked.resolvedRefs().get(ref))).get(0).nodeId();
            String unit=unit(physical,checked.compiled(),new HashSet<>());
            var rule=rules.addObject().put("id","condition_"+(++index)).put("valueRef",ref)
                    .put("resolvedValueRef",checked.resolvedRefs().get(ref))
                    .put("operator",condition.path("operator").asText()).put("unit",unit)
                    .put("expression",expression(physical,checked.compiled()));
            rule.set("threshold",condition.path("threshold"));
            String display=rule.path("expression").asText()+" "+operator(rule.path("operator").asText())+" "
                    +condition.path("threshold").decimalValue().stripTrailingZeros().toPlainString()+unitLabel(unit);
            rule.put("description",display);
        }
        result.put("relation","AND").put("checkIntervalSeconds",300).put("rearmPolicy","AFTER_NO_MATCH");
        return result;
    }
    private PlanNode node(String id,CompiledPlan plan) { return plan.dagPlan().nodes().stream().filter(n->n.id().equals(id)).findFirst().orElseThrow(); }
    private String unit(String id,CompiledPlan plan,Set<String> visiting) {
        var node=node(id,plan);
        if(node.metric()!=null) return node.metric().unit();
        if(Set.of("ratio","relative_change","annualize").contains(node.tool())) return "ratio";
        if("compare".equals(node.tool())) return "comparison";
        if(!visiting.add(id)) throw new IllegalArgumentException("循环单位依赖");
        var units=new HashSet<String>();
        for(var ref:ReferenceResolver.findRefs(node.args())) {
            if(ref.path().equals(".data.percent")) units.add("percent");
            else if(ref.path().equals(".data.observation_seconds") || ref.path().equals(".data.period_seconds")) units.add("second");
            else units.add(unit(ref.nodeId(),plan,new HashSet<>(visiting)));
        }
        if(units.size()!=1) throw new IllegalArgumentException("无法为监控条件确定唯一单位，请使用同单位的输入");
        return "difference".equals(node.tool()) && units.contains("percent")?"percentage_point":units.iterator().next();
    }
    private String expression(String id,CompiledPlan plan) {
        var node=node(id,plan);
        if(node.metric()!=null) {
            var metric=node.metric(); var request=plan.logicalPlan().metrics().stream().filter(m->m.id().equals(metric.groupId())).findFirst().orElseThrow();
            String window=request.params() instanceof TimeQueryParams p && p.time() instanceof TimeSpec.RollingWindow r
                    ?"最近 "+r.duration().toDuration().toMinutes()+" 分钟 ":"当前 ";
            String name=switch(metric.metric()) {
                case "price.change_pct" -> "成交K线首开至末收变化率"; case "oi.change_pct" -> "起止采样点持仓量变化率";
                case "price.last" -> "成交价"; case "price.close" -> "K线收盘价";
                case "oi.quantity" -> "持仓量"; default -> metric.description();
            };
            String market = "spot".equals(metric.instrument().path("market_type").asText()) ? "现货" : "永续";
            return (metric.exchange().equals("binance") ? "Binance " : "OKX ")
                    + metric.instrument().path("base").asText() + "/USDT " + market + "，" + window + name;
        }
        String op=switch(node.tool()) { case "avg"->"均值"; case "sum"->"合计"; case "min"->"最小值"; case "max"->"最大值";
            case "difference"->"差值"; case "ratio"->"比值"; case "relative_change"->"相对变化率"; case "annualize"->"年化结果"; case "compare"->"大小关系值"; default->node.tool(); };
        return op+"（"+arguments(node.args(),plan)+"）";
    }
    private String arguments(JsonNode input,CompiledPlan plan) {
        if(input.isTextual()) {
            var refs=ReferenceResolver.findRefs(input);
            if(refs.size()==1) {
                var ref=refs.get(0);
                String suffix=ref.path().replace(".data","");
                return expression(ref.nodeId(),plan)+(".value".equals(suffix)?"":" ["+suffix+"]");
            }
            return input.asText();
        }
        if(input.isObject()) {
            var parts=new ArrayList<String>();
            input.fields().forEachRemaining(e->parts.add(e.getKey()+"="+arguments(e.getValue(),plan)));
            return String.join("；",parts);
        }
        if(input.isArray()) {
            var parts=new ArrayList<String>(); input.forEach(v->parts.add(arguments(v,plan))); return "["+String.join("，",parts)+"]";
        }
        return input.toString();
    }
    public static String operator(String op) { return switch(op) { case "GT"->"大于"; case "GTE"->"大于等于"; case "LT"->"小于"; case "LTE"->"小于等于"; default->throw new IllegalArgumentException("无效比较符"); }; }
    public static String unitLabel(String unit) { return switch(unit) { case "percent"->"%"; case "percentage_point"->" 个百分点";
        case "quote"->" USDT"; case "base"->"（标的币数量）"; case "contract"->" 张"; case "ratio"->"（比例/倍数）"; case "comparison"->"（-1/0/1）"; default->" "+unit; }; }
}
