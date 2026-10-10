package com.cexpilot.dag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行计划：一组带依赖关系的工具调用节点。
 * 当前由 MetricPlanCompiler 将 LLM 输出的 metrics/calculations 逻辑计划编译为本类。
 * 结构合法性由 PlanValidator 校验（本类只负责解析，容忍字段缺省）。
 */
public record DagPlan(List<PlanNode> nodes) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DagPlan {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * 从旧版 nodes 格式或内部序列化结果解析 Plan：{"nodes": [{"id", "tool", "args", "depends_on"}]}。
     * 当前 LLM 输出协议为 metrics/calculations，由 MetricPlanCompiler 编译后再生成 DagPlan；
     * 本方法主要用于测试构造及内部序列化回读。
     * args 缺省/非对象按空对象处理，depends_on 缺省按空表处理；
     * id / tool 缺失保留为 null，交给 PlanValidator 报错。
     */
    public static DagPlan fromJson(JsonNode json) {
        JsonNode nodesNode = json == null ? null : json.path("nodes");
        if (nodesNode == null || !nodesNode.isArray()) {
            throw new IllegalArgumentException("plan 缺少 nodes 数组");
        }
        List<PlanNode> nodes = new ArrayList<>();
        for (JsonNode node : nodesNode) {
            String id = node.path("id").isTextual() ? node.path("id").asText() : null;
            String tool = node.path("tool").isTextual() ? node.path("tool").asText() : null;
            JsonNode args = node.path("args").isObject() ? node.path("args") : MAPPER.createObjectNode();
            List<String> dependsOn = new ArrayList<>();
            JsonNode depsNode = node.path("depends_on");
            if (depsNode.isArray()) {
                for (JsonNode dep : depsNode) {
                    if (dep.isTextual()) {
                        dependsOn.add(dep.asText());
                    }
                }
            }
            nodes.add(new PlanNode(id, tool, args, dependsOn));
        }
        return new DagPlan(nodes);
    }

    public PlanNode node(String id) {
        for (PlanNode node : nodes) {
            if (node.id() != null && node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    /** 便于 trace 落库的序列化（字段名与 LLM 输出协议一致）。 */
    public ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        var nodesArray = root.putArray("nodes");
        for (PlanNode node : nodes) {
            ObjectNode n = nodesArray.addObject();
            n.put("id", node.id());
            if (node.metric() != null) {
                n.put("type", "metric");
                n.put("provider", node.metric().provider());
            } else {
                n.put("type", "tool");
                n.put("tool", node.tool());
            }
            n.set("args", node.args());
            if (node.metric() != null) n.set("metric_binding", node.metric().identity());
            var deps = n.putArray("depends_on");
            node.dependsOn().forEach(deps::add);
        }
        return root;
    }
}
