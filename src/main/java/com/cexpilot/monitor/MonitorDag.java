package com.cexpilot.monitor;

import com.cexpilot.dag.*;
import com.cexpilot.metric.*;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 固定物理DAG的存储协议。加载只恢复类型化查询，不读取目录默认值、不重新编译LogicalPlan。 */
final class MonitorDag {
    private MonitorDag() {}
    static ObjectNode write(DagPlan plan) { return plan.toJson().put("schemaVersion",1); }
    static DagPlan read(JsonNode saved,MetricProviderRegistry providers) {
        if(saved.path("schemaVersion").asInt()!=1 || !saved.path("nodes").isArray() || saved.path("nodes").isEmpty())
            throw new IllegalArgumentException("此规则未保存受支持的固定DAG，请重新创建并确认监控");
        var nodes=new ArrayList<PlanNode>();
        for(var n:saved.path("nodes")) {
            if(!n.path("id").isTextual() || !n.path("args").isObject() || !n.path("depends_on").isArray())
                throw new IllegalArgumentException("固定DAG节点格式无效");
            var deps=new ArrayList<String>();
            for(var dep:n.path("depends_on")) {
                if(!dep.isTextual()) throw new IllegalArgumentException("固定DAG依赖格式无效");
                deps.add(dep.asText());
            }
            var args=n.path("args").deepCopy(); MetricQuery query=null; String tool=null;
            if("metric".equals(n.path("type").asText())) {
                var b=n.path("metric_binding");
                var source=b.path("source");
                var binding=new MetricBinding(b.path("group_id").asText(),b.path("metric").asText(),b.path("exchange").asText(),
                        b.path("query_shape").asText(),b.path("instrument"),b.path("unit").asText(),source.path("provider").asText(),
                        providers.get(source.path("provider").asText()).selector(source.path("selector").asText()),
                        b.path("catalog_version").asText(),b.path("description").asText());
                query=switch(QueryShape.from(binding.shape())) {
                    case RANGE_STATISTIC,TIME_SERIES -> new TimeRangeQuery(binding,TimeSpecParser.parse(args.path("time")),
                            args.hasNonNull("interval")?args.path("interval").asText():null,args.path("include_unclosed").asBoolean());
                    case SNAPSHOT -> new SnapshotQuery(binding,args.hasNonNull("depth")?args.path("depth").asInt():null);
                    default -> throw new IllegalArgumentException("固定监控DAG不支持此查询形态");
                };
            } else if("tool".equals(n.path("type").asText())) tool=n.path("tool").asText();
            else throw new IllegalArgumentException("固定DAG节点类型无效");
            nodes.add(new PlanNode(n.path("id").asText(),tool,args,deps,query));
        }
        return new DagPlan(nodes);
    }
    /** 先得到完整的拓扑顺序；后续复用集合只扫描一次，不依赖持久化数组的先后顺序。 */
    static List<PlanNode> topological(DagPlan plan) {
        var remaining=new LinkedHashMap<String,PlanNode>(); var ready=new ArrayDeque<String>();
        var degree=new HashMap<String,Integer>(); var children=new HashMap<String,List<String>>();
        for(var node:plan.nodes()) {
            if(node.id()==null || node.id().isBlank() || remaining.putIfAbsent(node.id(),node)!=null)
                throw new IllegalArgumentException("固定DAG节点id无效或重复");
            degree.put(node.id(),node.dependsOn().size());
        }
        for(var node:plan.nodes()) for(var dep:node.dependsOn()) {
            if(!remaining.containsKey(dep)) throw new IllegalArgumentException("固定DAG依赖不存在");
            children.computeIfAbsent(dep,k->new ArrayList<>()).add(node.id());
        }
        for(var node:plan.nodes()) if(degree.get(node.id())==0) ready.add(node.id());
        var order=new ArrayList<PlanNode>();
        while(!ready.isEmpty()) {
            String id=ready.remove(); order.add(remaining.get(id));
            for(var child:children.getOrDefault(id,List.of())) if(degree.compute(child,(k,v)->v-1)==0) ready.add(child);
        }
        if(order.size()!=plan.nodes().size()) throw new IllegalArgumentException("固定DAG存在循环依赖");
        return order;
    }
}
