package com.cexpilot.metric;

import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JSON 逻辑计划 → 类型化 {@link LogicalPlan}。
 * 负责字段类型、必填项、未知字段和形态对应参数的校验；不绑定 Provider，也不按交易所展开。
 */
public final class LogicalPlanParser {

    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Set<String> ALLOWED_EXCHANGES = Set.of("binance", "okx");
    private static final Set<String> PLAN_FIELDS = Set.of("metrics", "calculations");
    private static final Set<String> METRIC_COMMON_FIELDS = Set.of(
            "id", "metric", "exchanges", "instrument", "query_shape");
    private static final Set<String> CALCULATION_FIELDS = Set.of("id", "operator", "input");

    public LogicalPlan parse(JsonNode plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null || !plan.isObject()) {
            throw new IllegalArgumentException("plan 必须为对象");
        }
        unknownFields(plan, PLAN_FIELDS, "plan", errors);
        JsonNode metricsNode = plan.path("metrics");
        JsonNode calculationsNode = plan.path("calculations");
        if (!metricsNode.isArray()) errors.add("plan.metrics 必须为数组");
        if (!calculationsNode.isArray()) errors.add("plan.calculations 必须为数组");
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));

        List<MetricRequest> metrics = new ArrayList<>();
        List<CalculationRequest> calculations = new ArrayList<>();

        for (int i = 0; i < metricsNode.size(); i++) {
            MetricRequest request = parseMetric(metricsNode.get(i), "metrics[" + i + "]", errors);
            if (request != null) metrics.add(request);
        }
        for (int i = 0; i < calculationsNode.size(); i++) {
            CalculationRequest request = parseCalculation(calculationsNode.get(i), "calculations[" + i + "]", errors);
            if (request != null) calculations.add(request);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return new LogicalPlan(metrics, calculations);
    }

    private MetricRequest parseMetric(JsonNode node, String path, List<String> errors) {
        if (!node.isObject()) {
            errors.add(path + " 必须为对象");
            return null;
        }
        String id = text(node, "id", path, errors);
        if (id != null && (!ID.matcher(id).matches() || id.startsWith("metric_"))) {
            errors.add(path + ".id 必须是字母开头的字母数字下划线，且不能使用 metric_ 前缀");
        }
        String metric = text(node, "metric", path, errors);
        String shapeCode = text(node, "query_shape", path, errors);
        QueryShape shape = null;
        if (shapeCode != null) {
            try {
                shape = QueryShape.from(shapeCode);
            } catch (IllegalArgumentException e) {
                errors.add(path + ".query_shape " + e.getMessage());
            }
        }
        InstrumentSpec instrument = parseInstrument(node.path("instrument"), path + ".instrument", errors);
        List<String> exchanges = parseExchanges(node.path("exchanges"), path + ".exchanges", errors);
        QueryParams params = parseParams((ObjectNode) node, shape, path, errors);

        Set<String> allowed = new LinkedHashSet<>(METRIC_COMMON_FIELDS);
        if (shape != null) allowed.addAll(shapeFields(shape));
        unknownFields(node, allowed, path, errors);

        if (id == null || metric == null || shape == null || instrument == null || exchanges == null) {
            return null;
        }
        return new MetricRequest(id, metric, instrument, shape, exchanges, params);
    }

    private InstrumentSpec parseInstrument(JsonNode node, String path, List<String> errors) {
        if (!node.isObject()) {
            errors.add(path + " 必须为对象");
            return null;
        }
        String marketType = text(node, "market_type", path, errors);
        String base = text(node, "base", path, errors);
        String quote = text(node, "quote", path, errors);
        String settle = node.has("settle") && !node.path("settle").isNull()
                ? text(node, "settle", path, errors) : null;
        Set<String> allowed = Set.of("market_type", "base", "quote", "settle");
        unknownFields(node, allowed, path, errors);
        if (marketType == null || base == null || quote == null) return null;
        return new InstrumentSpec(marketType, base, quote, settle);
    }

    private List<String> parseExchanges(JsonNode node, String path, List<String> errors) {
        if (!node.isArray() || node.isEmpty()) {
            errors.add(path + " 必须为非空数组");
            return null;
        }
        List<String> exchanges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isTextual()) {
                errors.add(path + "[" + i + "] 必须为字符串");
                continue;
            }
            String ex = item.asText();
            if (!ALLOWED_EXCHANGES.contains(ex)) {
                errors.add(path + "[" + i + "] 仅支持 binance/okx");
                continue;
            }
            if (!seen.add(ex)) {
                errors.add(path + " 不能重复: " + ex);
                continue;
            }
            exchanges.add(ex);
        }
        return exchanges;
    }

    private QueryParams parseParams(ObjectNode node, QueryShape shape, String path, List<String> errors) {
        if (shape == null) return null;
        return switch (shape) {
            case RANGE_STATISTIC, TIME_SERIES -> parseTimeParams(node, path, errors);
            case RECENT_N -> parseCountParams(node, path, errors);
            case SNAPSHOT, OFFICIAL_24H -> parseSnapshotParams(node, path, errors);
        };
    }

    private TimeQueryParams parseTimeParams(ObjectNode node, String path, List<String> errors) {
        JsonNode timeNode = node.path("time");
        if (!timeNode.isObject()) {
            errors.add(path + ".time " + (timeNode.isMissingNode() ? "缺少非空字符串: type" : "必须为对象"));
            return null;
        }
        TimeSpec time;
        try {
            time = TimeSpecParser.parse(timeNode);
        } catch (IllegalArgumentException e) {
            errors.add(path + ".time " + e.getMessage());
            return null;
        }
        String interval = node.has("interval") ? text(node, "interval", path, errors) : null;
        Boolean includeUnclosed = node.has("include_unclosed")
                ? bool(node, "include_unclosed", path, errors) : null;
        return new TimeQueryParams(time, interval, includeUnclosed);
    }

    private CountQueryParams parseCountParams(ObjectNode node, String path, List<String> errors) {
        // recent_n 形态模型常会塞一个空 time；空对象/显式 null 静默剥离，非空则报错。
        if (node.has("time")) {
            JsonNode time = node.get("time");
            if (!time.isNull() && !(time.isObject() && time.isEmpty())) {
                errors.add(path + ".time 必须省略（recent_n 不接受时间窗口；不得把天数或小时数换成 count）");
            }
        }
        Integer count = integer(node, "count", path + ".count", errors);
        if (count == null) return null;
        return new CountQueryParams(count);
    }

    private SnapshotQueryParams parseSnapshotParams(ObjectNode node, String path, List<String> errors) {
        for (String field : List.of("time", "interval", "include_unclosed")) {
            if (node.has(field)) {
                errors.add(path + "." + field + " 必须省略（" + "snapshot/official_24h 不接受时间窗口与粒度参数）");
            }
        }
        Integer depth = node.has("depth") ? integer(node, "depth", path + ".depth", errors) : null;
        return new SnapshotQueryParams(depth);
    }

    private CalculationRequest parseCalculation(JsonNode node, String path, List<String> errors) {
        if (!node.isObject()) {
            errors.add(path + " 必须为对象");
            return null;
        }
        String id = text(node, "id", path, errors);
        if (id != null && (!ID.matcher(id).matches() || id.startsWith("metric_"))) {
            errors.add(path + ".id 必须是字母开头的字母数字下划线，且不能使用 metric_ 前缀");
        }
        String operator = text(node, "operator", path, errors);
        JsonNode input = node.path("input");
        if (!input.isObject()) {
            errors.add(path + ".input 必须为对象");
        }
        unknownFields(node, CALCULATION_FIELDS, path, errors);
        if (id == null || operator == null || !input.isObject()) return null;
        return new CalculationRequest(id, operator, input);
    }

    private Set<String> shapeFields(QueryShape shape) {
        return switch (shape) {
            case RANGE_STATISTIC, TIME_SERIES -> Set.of("time", "interval", "include_unclosed");
            // recent_n 形态允许出现空 time（模型常见误写），非空时由 parseCountParams 单独报错。
            case RECENT_N -> Set.of("count", "time");
            case SNAPSHOT, OFFICIAL_24H -> Set.of("depth");
        };
    }

    private String text(JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add(path + "." + field + " 必须为字符串");
            return null;
        }
        return value.asText();
    }

    private Integer integer(JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            errors.add(path + " 必须为整数且在 int 范围内");
            return null;
        }
        return value.asInt();
    }

    private Boolean bool(JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            errors.add(path + "." + field + " 必须为布尔值");
            return null;
        }
        return value.asBoolean();
    }

    private void unknownFields(JsonNode node, Set<String> allowed, String path, List<String> errors) {
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            errors.add(path + " 含未知字段: " + String.join(", ", unknown));
        }
    }
}
