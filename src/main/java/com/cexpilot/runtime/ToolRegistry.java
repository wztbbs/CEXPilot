package com.cexpilot.runtime;

import com.cexpilot.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 YAML 元数据与同名 Java 执行器绑定；disabled 工具不暴露、不执行。 */
@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    @Autowired
    public ToolRegistry(List<AgentTool> toolList, ResourceLoader loader) {
        this(toolList, ToolDefinitionLoader.load(loader));
    }

    public ToolRegistry(List<AgentTool> toolList, List<ToolDefinition> configured) {
        Map<String, AgentTool> executors = new LinkedHashMap<>();
        for (AgentTool tool : toolList) {
            if (executors.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalStateException("重复工具执行器: " + tool.name());
        }
        Map<String, ToolDefinition> all = new LinkedHashMap<>();
        for (ToolDefinition definition : configured) {
            if (all.putIfAbsent(definition.name(), definition) != null)
                throw new IllegalStateException("重复工具 YAML: " + definition.name());
            if (definition.enabled()) {
                AgentTool executor = executors.get(definition.name());
                if (executor == null) throw new IllegalStateException("工具缺少执行器: " + definition.name());
                tools.put(definition.name(), executor);
                definitions.put(definition.name(), definition);
            }
        }
        for (String name : executors.keySet()) {
            if (!all.containsKey(name)) throw new IllegalStateException("工具缺少 YAML: " + name);
        }
    }

    public AgentTool get(String name) { return tools.get(name); }

    public ToolSpec spec(String name) {
        ToolDefinition definition = definitions.get(name);
        return definition == null ? null : definition.spec();
    }

    public List<ToolSpec> specs() { return definitions.values().stream().map(ToolDefinition::spec).toList(); }

    /** 引用已解析后再次校验，并应用同一份 YAML 的默认值。 */
    public JsonNode prepareArguments(String name, JsonNode args) {
        ToolSpec spec = spec(name);
        if (spec == null) throw new IllegalArgumentException("未注册或已禁用的工具: " + name);
        List<String> errors = ToolArguments.validate(args, spec.inputSchema(), false);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return ToolArguments.withDefaults(args, spec.inputSchema());
    }

    public int size() { return tools.size(); }
}
