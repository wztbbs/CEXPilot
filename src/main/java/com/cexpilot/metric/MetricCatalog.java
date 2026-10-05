package com.cexpilot.metric;

import com.cexpilot.calculation.CalculationTool;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ToolOutputSchema;
import com.cexpilot.runtime.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 指标语义与物理绑定同源；只将业务契约呈现给 Planner。 */
@Component
public class MetricCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonNode document;

    public MetricCatalog(ResourceLoader loader) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = loader.getResource("classpath:metrics/kline.yml").getInputStream()) {
            document = MAPPER.valueToTree(new Yaml(new SafeConstructor(options)).load(input));
            if (!document.path("metrics").isObject() || document.path("metrics").isEmpty()
                    || !document.path("operators").isObject() || !document.path("concepts").isTextual()) {
                throw new IllegalArgumentException("指标目录必须有 metrics/operators/concepts");
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载指标目录失败", e);
        }
    }

    public String version() { return document.path("version").asText() + ":" + PromptStore.fingerprint(document.toString()); }
    public String concepts() { return document.path("concepts").asText(); }
    public Set<String> operators() {
        var names = new java.util.LinkedHashSet<String>();
        document.path("operators").fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
    public JsonNode definition(String metric) {
        JsonNode result = document.path("metrics").get(metric);
        if (result == null) throw new IllegalArgumentException("未接入指标: " + metric);
        return result;
    }
    public JsonNode binding(String metric, String shape) {
        JsonNode result = definition(metric).path("bindings").get(shape);
        if (result == null) throw new IllegalArgumentException("指标 " + metric + " 不支持查询形态 " + shape);
        return result;
    }
    public List<String> names() {
        List<String> names = new ArrayList<>();
        document.path("metrics").fieldNames().forEachRemaining(names::add);
        return names;
    }
    public String describeMetrics() {
        StringBuilder out = new StringBuilder();
        document.path("metrics").fields().forEachRemaining(e -> {
            List<String> shapes = new ArrayList<>();
            e.getValue().path("bindings").fieldNames().forEachRemaining(shapes::add);
            out.append("- ").append(e.getKey()).append(": ").append(e.getValue().path("description").asText())
                    .append("；unit=").append(e.getValue().path("unit").asText())
                    .append("；query_shape=").append(String.join("|", shapes)).append('\n');
        });
        return out.toString();
    }
    public String describeOperators(ToolRegistry registry) {
        StringBuilder out = new StringBuilder();
        document.path("operators").fields().forEachRemaining(e -> {
            if (registry.get(e.getKey()) instanceof CalculationTool) {
                var output = registry.outputSchema(e.getKey()).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) output.path("properties")).remove(List.of("unit", "metric_sources"));
                out.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append('\n')
                        .append("  输出: ").append(ToolOutputSchema.describe(output)).append('\n');
            }
        });
        return out.toString();
    }
    /** 启动校验 Provider 选择器与形态，完全独立于旧 Tool 的注册和 JSON schema。 */
    public void validateBindings() {
        for (String metric : names()) {
            JsonNode definition = definition(metric);
            if (!definition.path("description").isTextual()
                    || !Set.of("base", "quote", "percent").contains(definition.path("unit").asText())
                    || !definition.path("bindings").isObject() || definition.path("bindings").isEmpty()) {
                throw new IllegalStateException("指标定义无效: " + metric);
            }
            definition.path("bindings").fields().forEachRemaining(e -> {
                JsonNode binding = e.getValue();
                try {
                    KlineMetric selector = KlineMetric.valueOf(binding.path("selector").asText());
                    if (!KlineMetricProvider.NAME.equals(binding.path("provider").asText()) || !selector.supports(e.getKey())) {
                        throw new IllegalArgumentException("不支持的 Provider 或查询形态");
                    }
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException("指标绑定不可用: " + metric + "/" + e.getKey(), ex);
                }
            });
        }
    }
}
