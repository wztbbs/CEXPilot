package com.cexpilot.metric;

import com.cexpilot.calculation.CalculationTool;
import com.cexpilot.dag.DagPlan;
import com.cexpilot.dag.PlanNode;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.*;
import java.util.regex.Pattern;

/** 紧凑逻辑计划 → 现有执行 DAG。没有旧协议回退，也不允许模型选择底层 Tool。 */
public final class MetricPlanCompiler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Pattern REF = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z0-9_]+)+}}");
    private final MetricCatalog catalog;
    private final ToolRegistry registry;

    public MetricPlanCompiler(MetricCatalog catalog, ToolRegistry registry) {
        this.catalog = catalog;
        this.registry = registry;
        catalog.validateBindings();
    }

    public Set<String> allowedTools() {
        Set<String> result = new HashSet<>(catalog.operators());
        return result;
    }

    /** 修复反馈也使用逻辑引用，避免要求模型理解编译后的 ID 和 Tool 字段。 */
    public List<String> logicalErrors(DagPlan plan, List<String> errors) {
        return errors.stream().map(error -> {
            String message = error;
            for (PlanNode node : plan.nodes()) {
                if (node.metric() != null) {
                    String logical = node.metric().groupId() + "." + node.metric().exchange();
                    message = message.replace("{{" + node.id() + ".data.", "{{" + logical + ".")
                            .replace(node.id() + " ", logical + " ");
                } else {
                    message = message.replace("{{" + node.id() + ".data.", "{{" + node.id() + ".");
                }
            }
            return message;
        }).toList();
    }

    public DagPlan compile(JsonNode plan, int maxNodes) {
        object(plan, Set.of("metrics", "calculations"), "plan");
        JsonNode metrics = array(plan, "metrics");
        JsonNode calculations = array(plan, "calculations");
        if ((long) metrics.size() + calculations.size() > maxNodes) fail("逻辑计划规模超过上限 " + maxNodes);
        Set<String> ids = new HashSet<>();
        Map<String, Map<String, String>> branches = new LinkedHashMap<>();
        Map<String, String> calcIds = new LinkedHashMap<>();
        List<PlanNode> nodes = new ArrayList<>();
        for (JsonNode metric : metrics) {
            object(metric, Set.of("id", "metric", "exchanges", "instrument", "query_shape", "time", "interval", "include_unclosed"), "metric");
            String id = id(metric, ids);
            String name = text(metric, "metric");
            String shape = text(metric, "query_shape");
            JsonNode mapping = catalog.binding(name, shape);
            JsonNode instrument = metric.path("instrument");
            object(instrument, Set.of("market_type", "base", "quote", "settle"), "instrument");
            if (!"perpetual".equals(text(instrument, "market_type")) || !"USDT".equals(text(instrument, "quote"))
                    || instrument.has("settle") && !"USDT".equals(text(instrument, "settle"))) {
                fail("当前指标仅支持 USDT 报价、USDT 结算永续，不支持该产品；不会自动替换");
            }
            String base = text(instrument, "base");
            if (!base.matches("[A-Z0-9]{1,20}")) fail("instrument.base 必须是大写基础币代码");
            validateTime(metric.path("time"));
            if (metric.has("interval") && (!metric.get("interval").isTextual()
                    || !Set.of("5m", "15m", "1h").contains(metric.get("interval").asText()))) fail("interval 仅支持 5m/15m/1h");
            if (metric.has("include_unclosed") && !metric.get("include_unclosed").isBoolean()) fail("include_unclosed 必须是布尔值");
            Map<String, String> expanded = new LinkedHashMap<>();
            JsonNode exchanges = array(metric, "exchanges");
            if (exchanges.isEmpty()) fail("exchanges 不能为空");
            for (JsonNode exchange : exchanges) {
                if (!exchange.isTextual() || !Set.of("binance", "okx").contains(exchange.asText())) fail("exchanges 仅支持 binance/okx");
                String ex = exchange.asText();
                String physicalId = "metric_" + nodes.size();
                if (expanded.putIfAbsent(ex, physicalId) != null) fail("exchanges 不能重复: " + ex);
                ObjectNode args = MAPPER.createObjectNode().put("exchange", ex).put("symbol", base)
                        .put("market_type", "perpetual").put("quote_asset", "USDT");
                args.set("time", metric.get("time").deepCopy());
                for (String option : List.of("interval", "include_unclosed")) if (metric.has(option)) args.set(option, metric.get(option).deepCopy());
                ObjectNode normalizedInstrument = instrument.deepCopy();
                normalizedInstrument.put("settle", "USDT");
                String unit = switch (catalog.definition(name).path("unit").asText()) {
                    case "base" -> base;
                    case "quote" -> "USDT";
                    default -> "percent";
                };
                String provider = mapping.path("provider").asText();
                MetricBinding binding = new MetricBinding(id, name, ex, shape, normalizedInstrument, unit, provider,
                        KlineMetric.valueOf(mapping.path("selector").asText()), catalog.version());
                MetricQuery query = new MetricQuery(binding, TimeSpecParser.parse(metric.get("time")),
                        metric.has("interval") ? com.cexpilot.time.CandleInterval.parse(metric.get("interval").asText()) : null,
                        metric.path("include_unclosed").asBoolean(false));
                nodes.add(new PlanNode(physicalId, null, args, List.of(), query));
                if (nodes.size() + calculations.size() > maxNodes) fail("展开后的执行节点超过上限 " + maxNodes);
            }
            branches.put(id, expanded);
        }
        for (JsonNode calc : calculations) {
            object(calc, Set.of("id", "operator", "input"), "calculation");
            String id = id(calc, ids);
            String operator = text(calc, "operator");
            if (!catalog.operators().contains(operator) || !(registry.get(operator) instanceof CalculationTool)) {
                fail("未接入算子: " + operator);
            }
            if (!calc.path("input").isObject()) fail("calculation.input 必须为对象");
            calcIds.put(id, id); // metric 分支使用保留的物理前缀，计算 ID 与之冲突时显式拒绝。
            if (nodes.stream().anyMatch(n -> n.id().equals(id))) fail("计算 ID 与内部执行节点冲突: " + id);
        }
        for (JsonNode calc : calculations) {
            Set<String> deps = new LinkedHashSet<>();
            ObjectNode args = MAPPER.createObjectNode();
            args.set("input", rewrite(calc.get("input"), branches, calcIds, deps));
            nodes.add(new PlanNode(calc.get("id").asText(), calc.get("operator").asText(), args, List.copyOf(deps)));
        }
        return new DagPlan(nodes);
    }

    private JsonNode rewrite(JsonNode node, Map<String, Map<String, String>> branches,
                             Map<String, String> calculations, Set<String> deps) {
        if (node.isTextual() && (node.asText().contains("{{") || node.asText().contains("}}"))) {
            String value = node.asText();
            if (!REF.matcher(value).matches()) fail("引用必须是完整字段引用，不能包含表达式或插值: " + value);
            String[] parts = value.substring(2, value.length() - 2).split("\\.");
            String target;
            int offset;
            if (branches.containsKey(parts[0])) {
                if (parts.length < 3 || !branches.get(parts[0]).containsKey(parts[1])) fail("指标引用必须包含该组内的交易所: " + value);
                target = branches.get(parts[0]).get(parts[1]);
                offset = 2;
            } else {
                target = calculations.get(parts[0]);
                if (target == null) fail("引用不存在的指标组或计算节点: " + value);
                offset = 1;
            }
            deps.add(target);
            return TextNode.valueOf("{{" + target + ".data." + String.join(".", Arrays.copyOfRange(parts, offset, parts.length)) + "}}");
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), rewrite(e.getValue(), branches, calculations, deps)));
            return copy;
        }
        if (node.isArray()) {
            var copy = MAPPER.createArrayNode();
            node.forEach(child -> copy.add(rewrite(child, branches, calculations, deps)));
            return copy;
        }
        return node.deepCopy();
    }

    private static void validateTime(JsonNode time) {
        String type = text(time, "type");
        Set<String> keys = switch (type) {
            case "calendar_period" -> Set.of("type", "timezone", "unit", "offset", "segment", "extent");
            case "rolling_window" -> Set.of("type", "timezone", "duration");
            case "relative_day_range" -> Set.of("type", "timezone", "start", "end");
            case "absolute_range" -> Set.of("type", "timezone", "start", "end", "end_mode");
            default -> throw new IllegalArgumentException("不支持的 time.type: " + type);
        };
        object(time, keys, "time");
        if (time.hasNonNull("timezone") && !time.get("timezone").isTextual()) fail("timezone 必须是字符串或 null");
        if ("rolling_window".equals(type)) object(time.path("duration"), Set.of("value", "unit"), "duration");
        if (type.endsWith("_range")) for (String bound : List.of("start", "end")) {
            object(time.path(bound), "relative_day_range".equals(type) ? Set.of("day_offset", "time")
                    : Set.of("year", "month", "day", "time"), bound);
        }
        TimeSpecParser.parse(time);
    }
    private static String id(JsonNode node, Set<String> ids) {
        String id = text(node, "id");
        if (!ID.matcher(id).matches() || id.startsWith("metric_")) fail("id 必须是字母开头的字母数字下划线，且不能使用 metric_ 前缀");
        if (!ids.add(id)) fail("重复 id: " + id);
        return id;
    }
    private static JsonNode array(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isArray()) fail(key + " 必须为数组");
        return value;
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) fail("缺少非空字符串: " + key);
        return value.asText();
    }
    private static void object(JsonNode node, Set<String> fields, String name) {
        if (!node.isObject()) fail(name + " 必须为对象");
        node.fieldNames().forEachRemaining(key -> { if (!fields.contains(key)) fail(name + " 含未知字段: " + key); });
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
