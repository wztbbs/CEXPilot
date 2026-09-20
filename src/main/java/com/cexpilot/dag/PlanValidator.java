package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.runtime.ToolArguments;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan 确定性校验（不调 LLM），返回全部错误明细供 DagPlanner 喂回 LLM 修复：
 * - 结构：id 非空唯一、depends_on 引用存在、三色 DFS 判环；
 * - 供给：tool 已注册且在 allowedTools 白名单内；
 * - 入参：满足 inputSchema 的 required（值为具体值或 {{ref}} 均可，类型、枚举、范围等按 YAML 校验）；
 * - 规模：节点数 ≤ min(maxNodes, maxToolCalls)、拓扑深度 ≤ maxDepth；
 * - 引用闭合：每个 {{ref}} 的目标节点存在且在（传递）依赖闭包内。
 */
@Component
public class PlanValidator {

    private final ToolRegistry registry;
    private final DagConfig config;

    public PlanValidator(ToolRegistry registry, DagConfig config) {
        this.registry = registry;
        this.config = config;
    }

    /**
     * @param allowedTools      本轮允许使用的工具名子集；null 表示全量工具
     * @param maxToolCalls      本轮工具调用上限
     * @return 错误明细列表；空列表表示校验通过
     */
    public List<String> validate(DagPlan plan, Set<String> allowedTools, int maxToolCalls) {
        List<String> errors = new ArrayList<>();
        List<PlanNode> nodes = plan.nodes();
        if (nodes.isEmpty()) {
            errors.add("plan 不包含任何节点");
            return errors;
        }

        int nodeLimit = Math.min(config.getMaxNodes(), maxToolCalls);
        if (nodes.size() > nodeLimit) {
            errors.add("节点数 " + nodes.size() + " 超过上限 " + nodeLimit);
        }

        Map<String, PlanNode> byId = new HashMap<>();
        for (PlanNode node : nodes) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add("存在缺少 id 的节点");
                continue;
            }
            if (byId.put(node.id(), node) != null) {
                errors.add("节点 id 重复: " + node.id());
            }
        }

        for (PlanNode node : nodes) {
            validateTool(node, allowedTools, errors);
            validateArgs(node, errors);
            for (String dep : node.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    errors.add(node.id() + " 的 depends_on 引用了不存在的节点: " + dep);
                }
            }
        }

        if (hasCycle(nodes, byId, errors)) {
            return errors;  // 有环时深度与拓扑无意义，提前返回
        }
        validateDepth(nodes, byId, errors);
        validateRefs(nodes, byId, errors);
        return errors;
    }

    private void validateTool(PlanNode node, Set<String> allowedTools, List<String> errors) {
        if (node.tool() == null || node.tool().isBlank()) {
            errors.add(node.id() + " 缺少 tool 字段");
            return;
        }
        if (registry.get(node.tool()) == null) {
            errors.add(node.id() + " 引用了未注册的工具: " + node.tool());
        } else if (allowedTools != null && !allowedTools.contains(node.tool())) {
            errors.add(node.id() + " 的工具 " + node.tool() + " 不在本次允许的范围内");
        }
    }

    private void validateArgs(PlanNode node, List<String> errors) {
        var spec = registry.spec(node.tool());
        if (spec == null) return;
        for (String error : ToolArguments.validate(node.args(), spec.inputSchema(), true)) {
            errors.add(node.id() + " 工具 " + node.tool() + ": " + error);
        }
    }

    /** 三色 DFS 判环；发现环时追加错误并返回 true。 */
    private boolean hasCycle(List<PlanNode> nodes, Map<String, PlanNode> byId, List<String> errors) {
        Map<String, Integer> state = new HashMap<>(); // 0=未访问 1=访问中 2=已完成
        boolean found = false;
        for (PlanNode node : nodes) {
            if (node.id() != null && dfsCycle(node.id(), byId, state)) {
                errors.add("depends_on 存在环，涉及节点: " + node.id());
                found = true;
            }
        }
        return found;
    }

    private boolean dfsCycle(String id, Map<String, PlanNode> byId, Map<String, Integer> state) {
        int s = state.getOrDefault(id, 0);
        if (s == 1) {
            return true;
        }
        if (s == 2) {
            return false;
        }
        state.put(id, 1);
        PlanNode node = byId.get(id);
        boolean cycle = false;
        if (node != null) {
            for (String dep : node.dependsOn()) {
                if (byId.containsKey(dep) && dfsCycle(dep, byId, state)) {
                    cycle = true;
                }
            }
        }
        state.put(id, 2);
        return cycle;
    }

    /** 拓扑深度 = Kahn 层数（最长依赖链）。 */
    private void validateDepth(List<PlanNode> nodes, Map<String, PlanNode> byId, List<String> errors) {
        Map<String, Integer> depth = new HashMap<>();
        int maxDepth = 0;
        for (PlanNode node : nodes) {
            if (node.id() != null) {
                maxDepth = Math.max(maxDepth, depthOf(node.id(), byId, depth));
            }
        }
        if (maxDepth > config.getMaxDepth()) {
            errors.add("plan 深度 " + maxDepth + " 超过上限 " + config.getMaxDepth());
        }
    }

    private int depthOf(String id, Map<String, PlanNode> byId, Map<String, Integer> memo) {
        Integer cached = memo.get(id);
        if (cached != null) {
            return cached;
        }
        PlanNode node = byId.get(id);
        int depth = 1;
        if (node != null) {
            for (String dep : node.dependsOn()) {
                if (byId.containsKey(dep)) {
                    depth = Math.max(depth, depthOf(dep, byId, memo) + 1);
                }
            }
        }
        memo.put(id, depth);
        return depth;
    }

    /** 每个 {{ref}} 的目标节点必须存在，且在引用方的（传递）依赖闭包内——否则运行期拿不到结果。 */
    private void validateRefs(List<PlanNode> nodes, Map<String, PlanNode> byId, List<String> errors) {
        for (PlanNode node : nodes) {
            if (node.id() == null) {
                continue;
            }
            for (ReferenceResolver.Ref ref : ReferenceResolver.findRefs(node.args())) {
                PlanNode target = byId.get(ref.nodeId());
                if (target == null) {
                    errors.add(node.id() + " 引用了不存在的节点: " + ref.nodeId());
                    continue;
                }
                if (ref.nodeId().equals(node.id())) {
                    errors.add(node.id() + " 引用了自身: " + ref.nodeId());
                    continue;
                }
                if (!transitiveDeps(node.id(), byId).contains(ref.nodeId())) {
                    errors.add(node.id() + " 引用了 " + ref.nodeId() + " 但未（传递）依赖它，请加入 depends_on");
                }
            }
        }
    }

    private Set<String> transitiveDeps(String id, Map<String, PlanNode> byId) {
        Set<String> closure = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        PlanNode node = byId.get(id);
        if (node != null) {
            stack.addAll(node.dependsOn());
        }
        while (!stack.isEmpty()) {
            String dep = stack.pop();
            if (closure.add(dep)) {
                PlanNode depNode = byId.get(dep);
                if (depNode != null) {
                    stack.addAll(depNode.dependsOn());
                }
            }
        }
        return closure;
    }
}
