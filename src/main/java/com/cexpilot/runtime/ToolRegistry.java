package com.cexpilot.runtime;

import com.cexpilot.llm.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring 收集所有 AgentTool bean，按名字注册。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            tools.put(tool.name(), tool);
        }
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    /** 按名字过滤出工具子集（Intent 路由用）；不存在的名字静默忽略。 */
    public List<ToolSpec> specs(Set<String> allowed) {
        return tools.values().stream()
                .filter(t -> allowed.contains(t.name()))
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    public int size() {
        return tools.size();
    }
}
