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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 指标语义与物理绑定同源；只将业务契约呈现给 Planner。 */
@Component
public class MetricCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String version;
    private final String concepts;
    private final Map<String, String> operators;
    private final Map<String, MetricDefinition> definitions;

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
        this.version = document.path("version").asText() + ":" + PromptStore.fingerprint(document.toString());
        this.concepts = document.path("concepts").asText();
        Map<String, String> operatorDefinitions = new LinkedHashMap<>();
        document.path("operators").fields().forEachRemaining(e -> {
            if (!e.getValue().isTextual() || e.getValue().asText().isBlank())
                throw new IllegalArgumentException("operators." + e.getKey() + " 必须为非空字符串");
            operatorDefinitions.put(e.getKey(), e.getValue().asText());
        });
        this.operators = java.util.Collections.unmodifiableMap(operatorDefinitions);
        Map<String, MetricDefinition> metrics = new LinkedHashMap<>();
        document.path("metrics").fields().forEachRemaining(e -> {
            String path = "metrics." + e.getKey();
            JsonNode node = e.getValue();
            requireObject(node, Set.of("description", "unit", "bindings"), path);
            String description = requiredText(node, "description", path);
            String unit = requiredText(node, "unit", path);
            if (!Set.of("base", "quote", "percent", "ratio", "contract").contains(unit))
                throw new IllegalArgumentException(path + ".unit 不支持: " + unit);
            if (!node.path("bindings").isObject() || node.path("bindings").isEmpty())
                throw new IllegalArgumentException(path + ".bindings 必须为非空对象");
            Map<QueryShape, MetricBindingDefinition> bindings = new LinkedHashMap<>();
            node.path("bindings").fields().forEachRemaining(b -> {
                QueryShape shape = QueryShape.from(b.getKey());
                String bindingPath = path + ".bindings." + b.getKey();
                MetricBindingDefinition binding = parseBinding(b.getValue(), bindingPath);
                if (shape.requiresCount() != (binding.count() != null))
                    throw new IllegalArgumentException(bindingPath + ".count 仅 recent_n 必须声明");
                if (binding.intervals() != null && !shape.requiresTime())
                    throw new IllegalArgumentException(bindingPath + ".intervals 仅时间形态可声明");
                if (binding.depth() != null && shape != QueryShape.SNAPSHOT && shape != QueryShape.OFFICIAL_24H)
                    throw new IllegalArgumentException(bindingPath + ".depth 仅快照形态可声明");
                bindings.put(shape, binding);
            });
            metrics.put(e.getKey(), new MetricDefinition(description, unit, bindings));
        });
        this.definitions = java.util.Collections.unmodifiableMap(metrics);
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

    public String version() { return version; }
    public String concepts() { return concepts; }
    public Set<String> operators() { return operators.keySet(); }

    public MetricDefinition metricDefinition(String metric) {
        MetricDefinition result = definitions.get(metric);
        if (result == null) throw new IllegalArgumentException("未接入指标: " + metric);
        return result;
    }

    private static MetricBindingDefinition parseBinding(JsonNode node, String path) {
        requireObject(node, Set.of("provider", "selector", "exchanges", "intervals", "count", "depth"), path);
        Set<String> exchanges = stringSet(node, "exchanges", path);
        if (exchanges != null && !Set.of("binance", "okx").containsAll(exchanges))
            throw new IllegalArgumentException(path + ".exchanges 仅支持 binance/okx");
        return new MetricBindingDefinition(requiredText(node, "provider", path), requiredText(node, "selector", path),
                exchanges, stringSet(node, "intervals", path),
                boundInteger(node, "count", path), boundInteger(node, "depth", path));
    }

    private static Set<String> stringSet(JsonNode node, String field, String path) {
        if (!node.has(field)) return null;
        JsonNode value = node.get(field);
        if (!value.isArray() || value.isEmpty())
            throw new IllegalArgumentException(path + "." + field + " 必须为非空字符串数组");
        Set<String> set = new java.util.LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || !set.add(item.asText()))
                throw new IllegalArgumentException(path + "." + field + " 必须为非空且不重复的字符串数组");
        }
        return set;
    }

    private static BoundInteger boundInteger(JsonNode node, String field, String path) {
        if (!node.has(field)) return null;
        JsonNode value = node.get(field);
        String boundPath = path + "." + field;
        requireObject(value, Set.of("min", "max", "default"), boundPath);
        int min = requiredInteger(value, "min", boundPath);
        int max = requiredInteger(value, "max", boundPath);
        Integer defaultValue = value.has("default") ? requiredInteger(value, "default", boundPath) : null;
        try { return new BoundInteger(min, max, defaultValue); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(boundPath + ": " + e.getMessage(), e); }
    }

    private static int requiredInteger(JsonNode node, String field, String path) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException(path + "." + field + " 必须为 int 范围内的整数");
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String field, String path) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException(path + "." + field + " 必须为非空字符串");
        return value.asText();
    }

    private static void requireObject(JsonNode node, Set<String> fields, String path) {
        if (!node.isObject()) throw new IllegalArgumentException(path + " 必须为对象");
        node.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new IllegalArgumentException(path + " 含未知字段: " + field);
        });
    }

    public List<String> names() { return List.copyOf(definitions.keySet()); }
    public String describeMetrics() {
        StringBuilder out = new StringBuilder();
        definitions.forEach((name, definition) -> {
            out.append("- ").append(name).append(": ").append(definition.description())
                    .append("；unit=").append(definition.unit());
            // 相同约束的形态合并展示；不同形态的交易所限制不能取并集，否则会虚报能力。
            var shapesByConstraints = new LinkedHashMap<String, List<String>>();
            definition.bindings().forEach((shape, binding) ->
                    shapesByConstraints.computeIfAbsent(describeConstraints(binding),
                            ignored -> new ArrayList<>()).add(shape.code()));
            shapesByConstraints.forEach((constraints, shapes) -> out.append("；query_shape=")
                    .append(String.join("|", shapes)).append(constraints));
            out.append('\n');
        });
        return out.toString();
    }

    /** 仅暴露 Planner 可用的参数约束，不暴露 provider/selector 等执行细节。 */
    private static String describeConstraints(MetricBindingDefinition binding) {
        List<String> constraints = new ArrayList<>();
        if (binding.exchanges() != null) constraints.add("exchanges=" + MAPPER.valueToTree(binding.exchanges()));
        if (binding.intervals() != null) constraints.add("intervals=" + MAPPER.valueToTree(binding.intervals()));
        if (binding.count() != null) constraints.add("count=" + describeBound(binding.count()));
        if (binding.depth() != null) constraints.add("depth=" + describeBound(binding.depth()));
        return constraints.isEmpty() ? "" : "（" + String.join("；", constraints) + "）";
    }

    private static JsonNode describeBound(BoundInteger bound) {
        var out = MAPPER.createObjectNode();
        if (bound.defaultValue() != null) out.put("default", bound.defaultValue());
        return out.put("min", bound.min()).put("max", bound.max());
    }
    public String describeOperators(ToolRegistry registry) {
        StringBuilder out = new StringBuilder();
        operators.forEach((name, description) -> {
            if (registry.get(name) instanceof CalculationTool) {
                var output = registry.outputSchema(name).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) output.path("properties")).remove(List.of("unit", "metric_sources"));
                out.append("- ").append(name).append(": ").append(description).append('\n')
                        .append("  输出: ").append(ToolOutputSchema.describe(output)).append('\n');
            }
        });
        return out.toString();
    }
}
