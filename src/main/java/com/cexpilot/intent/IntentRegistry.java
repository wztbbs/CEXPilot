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
 * 合并调用后 intent 从「路由依据」降级为「统计 hint + 回答约束」：不再按 intent 过滤工具；
 * intent 名落库用于统计，evidence_policy.rules 注入回答阶段 prompt，命中不了记 {@link #UNKNOWN}。
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
        return new IntentDefinition(name, description, parseEvidenceRules(doc));
    }

    /** evidence_policy.rules：注入回答 prompt 的该类问题回答要求；缺省为空。 */
    private static List<String> parseEvidenceRules(Map<String, Object> doc) {
        Object policy = doc.get("evidence_policy");
        if (!(policy instanceof Map<?, ?> policyMap)) {
            return List.of();
        }
        Object rules = policyMap.get("rules");
        if (!(rules instanceof List<?> ruleList)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object rule : ruleList) {
            result.add(String.valueOf(rule));
        }
        return result;
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
