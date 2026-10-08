package com.cexpilot.metric;

import com.cexpilot.calculation.CalculationTool;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ToolOutputSchema;
import com.cexpilot.runtime.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public MetricCatalog(ResourceLoader loader) {
        this(read(loader));
    }

    /** 测试注入：直接给定目录内容，用于校验尚未上线的形态与绑定；生产只走 ResourceLoader 构造。 */
    private MetricCatalog(JsonNode document) {
        if (!document.path("metrics").isObject() || document.path("metrics").isEmpty()
                || !document.path("operators").isObject() || !document.path("concepts").isTextual()) {
            throw new IllegalArgumentException("指标目录必须有 metrics/operators/concepts");
        }
        this.document = document;
    }

    static MetricCatalog of(JsonNode document) { return new MetricCatalog(document); }

    private static JsonNode read(ResourceLoader loader) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = loader.getResource("classpath:metrics/cex-perpetual.yml").getInputStream()) {
            return MAPPER.valueToTree(new Yaml(new SafeConstructor(options)).load(input));
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
        if (result == null) throw new IllegalArgumentException("当前系统未接入指标 " + metric + " 的查询形态 " + shape + "；这是系统接入限制，不代表交易所没有数据或算子不能计算");
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
                    .append("；query_shape=").append(String.join("|", shapes));
            List<String> exchanges = new ArrayList<>();
            e.getValue().path("bindings").forEach(binding -> binding.path("exchanges").forEach(exchange -> {
                if (exchange.isTextual() && !exchanges.contains(exchange.asText())) exchanges.add(exchange.asText());
            }));
            if (!exchanges.isEmpty()) out.append("；仅支持交易所: ").append(String.join(", ", exchanges));
            out.append('\n');
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
    /** 启动校验目录结构；Provider 存在性与选择器语义由 MetricProviderRegistry.validate 校验。 */
    public void validateBindings() {
        for (String metric : names()) {
            JsonNode definition = definition(metric);
            if (!definition.path("description").isTextual()
                    || !Set.of("base", "quote", "percent", "ratio", "contract").contains(definition.path("unit").asText())
                    || !definition.path("bindings").isObject() || definition.path("bindings").isEmpty()) {
                throw new IllegalStateException("指标定义无效: " + metric);
            }
            definition.path("bindings").fields().forEachRemaining(e -> {
                JsonNode binding = e.getValue();
                if (!binding.path("provider").isTextual() || binding.path("provider").asText().isBlank()
                        || !binding.path("selector").isTextual() || binding.path("selector").asText().isBlank()) {
                    throw new IllegalStateException("指标绑定缺少 provider/selector: " + metric + "/" + e.getKey());
                }
            });
        }
    }
}
