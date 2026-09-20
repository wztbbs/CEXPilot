package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 启动时加载 classpath:tools/*.yml；错误配置阻止启动，不回退到 Java 描述。 */
public final class ToolDefinitionLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolDefinitionLoader() {}

    public static List<ToolDefinition> load(ResourceLoader loader) {
        try {
            Resource[] resources = ResourcePatternUtils.getResourcePatternResolver(loader)
                    .getResources("classpath*:tools/*.yml");
            Arrays.sort(resources, Comparator.comparing(Resource::getDescription));
            List<ToolDefinition> definitions = new ArrayList<>();
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Resource resource : resources) {
                try (var input = resource.getInputStream()) {
                    Object loaded = yaml.load(input);
                    if (!(loaded instanceof Map<?, ?> doc)
                            || !(doc.get("name") instanceof String name)
                            || !(doc.get("description") instanceof String description)
                            || !(doc.get("enabled") instanceof Boolean enabled)) {
                        throw new IllegalArgumentException("必须配置 name、description 和布尔型 enabled");
                    }
                    definitions.add(new ToolDefinition(name, enabled, description.trim(),
                            MAPPER.valueToTree(doc.get("input_schema"))));
                } catch (Exception e) {
                    throw new IllegalStateException("工具配置错误 " + resource.getDescription() + ": " + e.getMessage(), e);
                }
            }
            return List.copyOf(definitions);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("加载工具 YAML 失败", e);
        }
    }
}
