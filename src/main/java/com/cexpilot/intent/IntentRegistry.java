package com.cexpilot.intent;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时加载 classpath:intents/*.yml 全部意图定义。
 * 文件缺失或解析失败直接启动报错（fail fast），避免路由静默失效。
 *
 * 合并调用后 intent 从「路由依据」降级为「统计 hint」：不再按 intent 过滤工具，
 * LLM 归类的 intent 名仅落库用于后续跟踪统计，命中不了记 {@link #UNKNOWN}。
 */
@Component
public class IntentRegistry {

    /** LLM 归类不到任何已注册 intent 时的兜底标记（不对应任何 yml 定义）。 */
    public static final String UNKNOWN = "UNKNOWN";

    private final Map<String, IntentDefinition> intents = new LinkedHashMap<>();

    public IntentRegistry(ResourceLoader resourceLoader) {
        ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        Yaml yaml = new Yaml();
        try {
            Resource[] resources = resolver.getResources("classpath*:intents/*.yml");
            for (Resource resource : resources) {
                IntentDefinition definition = parse(yaml, resource);
                intents.put(definition.name(), definition);
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载 intent 定义失败: " + e.getMessage(), e);
        }
    }

    private IntentDefinition parse(Yaml yaml, Resource resource) throws Exception {
        Map<String, Object> doc;
        try (InputStream in = resource.getInputStream()) {
            doc = yaml.load(in);
        }
        if (doc == null) {
            throw new IllegalStateException("空的 intent 文件: " + resource.getFilename());
        }
        String name = asString(doc.get("name"));
        String description = asString(doc.get("description"));
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("intent 缺少 name: " + resource.getFilename());
        }
        List<String> allowedTools = new ArrayList<>();
        Integer maxToolCalls = null;
        Object planner = doc.get("planner");
        if (planner instanceof Map<?, ?> plannerMap) {
            Object tools = plannerMap.get("allowed_tools");
            if (tools instanceof List<?> toolList) {
                for (Object tool : toolList) {
                    allowedTools.add(String.valueOf(tool));
                }
            }
            Object maxCalls = plannerMap.get("max_tool_calls");
            if (maxCalls instanceof Number number) {
                maxToolCalls = number.intValue();
            }
        }
        return new IntentDefinition(name, description, List.copyOf(allowedTools), maxToolCalls);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public List<IntentDefinition> all() {
        return List.copyOf(intents.values());
    }

    /** 找不到返回 null，由调用方按未命中处理。 */
    public IntentDefinition find(String name) {
        return name == null ? null : intents.get(name);
    }
}
