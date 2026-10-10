package com.cexpilot.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 根据指标目录解析指标绑定，检查产品、交易所、粒度、数量范围，应用默认值，生成类型化查询。
 */
public final class MetricQueryResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetricCatalog catalog;
    private final MetricProviderRegistry providers;

    public MetricQueryResolver(MetricCatalog catalog, MetricProviderRegistry providers) {
        this.catalog = catalog;
        this.providers = providers;
    }

    public List<ResolvedMetric> resolve(MetricRequest request, int startIndex) {
        MetricDefinition definition = catalog.metricDefinition(request.metric());
        MetricBindingDefinition binding = definition.bindings().get(request.shape());
        if (binding == null) {
            throw new IllegalArgumentException("当前系统未接入指标 " + request.metric() + " 的查询形态 "
                    + request.shape().code() + "；这是系统接入限制，不代表交易所没有数据或算子不能计算");
        }

        validateInstrument(request);

        List<String> errors = new ArrayList<>();
        Set<String> exchanges = new LinkedHashSet<>();
        for (String exchange : request.exchanges()) {
            if (!binding.supportsExchange(exchange)) {
                errors.add("exchanges 暂不支持交易所 " + exchange);
            } else if (!exchanges.add(exchange)) {
                errors.add(request.id() + ".exchanges 不能重复: " + exchange);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        List<ResolvedMetric> resolved = new ArrayList<>();
        int index = startIndex;
        for (String exchange : exchanges) {
            MetricBinding metricBinding = new MetricBinding(
                    request.id(), request.metric(), exchange, request.shape().code(),
                    normalizedInstrument(request.instrument()),
                    resolveUnit(definition.unit(), request.instrument().base(), exchange),
                    binding.provider(),
                    providers.get(binding.provider()).selector(binding.selector()),
                    catalog.version(), definition.description());
            MetricQuery query = buildQuery(request, binding, metricBinding);
            resolved.add(new ResolvedMetric("metric_" + index++, request, exchange, query));
        }
        return resolved;
    }

    private void validateInstrument(MetricRequest request) {
        InstrumentSpec instrument = request.instrument();
        if (!"perpetual".equals(instrument.marketType())) {
            throw new IllegalArgumentException("当前指标仅支持 USDT 报价、USDT 结算永续，不支持该产品；不会自动替换");
        }
        if (!"USDT".equals(instrument.quote()) || (instrument.settle() != null && !"USDT".equals(instrument.settle()))) {
            throw new IllegalArgumentException("当前指标仅支持 USDT 报价、USDT 结算永续，不支持该产品；不会自动替换");
        }
        if (!instrument.base().matches("[A-Z0-9]{1,20}")) {
            throw new IllegalArgumentException("instrument.base 必须是大写基础币代码");
        }
    }

    private MetricQuery buildQuery(MetricRequest request, MetricBindingDefinition binding, MetricBinding metricBinding) {
        QueryParams params = request.params();
        if (params instanceof TimeQueryParams p) {
            return buildTimeQuery(request, binding, metricBinding, p);
        } else if (params instanceof CountQueryParams p) {
            return buildCountQuery(request, binding, metricBinding, p);
        } else if (params instanceof SnapshotQueryParams p) {
            return buildSnapshotQuery(request, binding, metricBinding, p);
        }
        throw new IllegalArgumentException("未知查询参数类型: " + params.getClass());
    }

    private MetricQuery buildTimeQuery(MetricRequest request, MetricBindingDefinition binding,
                                       MetricBinding metricBinding, TimeQueryParams params) {
        if (!request.shape().requiresTime()) {
            throw new IllegalArgumentException(request.shape().code() + " 不接受时间窗口");
        }
        String interval = params.interval();
        if (interval != null) {
            if (!binding.hasIntervalSupport()) {
                throw new IllegalArgumentException("指标 " + request.metric() + " 为固定口径统计，不支持指定粒度参数");
            }
            if (!binding.supportsInterval(interval)) {
                throw new IllegalArgumentException("interval 仅支持 " + String.join("/", binding.intervals()));
            }
        }
        boolean includeUnclosed = params.includeUnclosed() != null && params.includeUnclosed();
        if (includeUnclosed && !binding.hasIntervalSupport()) {
            throw new IllegalArgumentException("指标 " + request.metric() + " 为固定口径统计，不支持 include_unclosed 参数");
        }
        return new TimeRangeQuery(metricBinding, params.time(), interval, includeUnclosed);
    }

    private MetricQuery buildCountQuery(MetricRequest request, MetricBindingDefinition binding,
                                        MetricBinding metricBinding, CountQueryParams params) {
        if (binding.count() == null) {
            throw new IllegalArgumentException("指标 " + request.metric() + " 的 " + request.shape().code() + " 不接受 count 参数");
        }
        if (!binding.count().contains(params.count())) {
            throw new IllegalArgumentException("count 必须在 " + binding.count().min() + "~" + binding.count().max() + " 之间");
        }
        return new CountQuery(metricBinding, params.count());
    }

    private MetricQuery buildSnapshotQuery(MetricRequest request, MetricBindingDefinition binding,
                                           MetricBinding metricBinding, SnapshotQueryParams params) {
        Integer depth = params.depth();
        if (depth != null) {
            if (binding.depth() == null) {
                throw new IllegalArgumentException("指标 " + request.metric() + " 不支持 depth 参数");
            }
            if (!binding.depth().contains(depth)) {
                throw new IllegalArgumentException("depth 必须是 " + binding.depth().min() + "~" + binding.depth().max() + " 的整数");
            }
        } else if (binding.depth() != null) {
            depth = binding.depth().defaultValue();
        }
        return new SnapshotQuery(metricBinding, depth);
    }

    private ObjectNode normalizedInstrument(InstrumentSpec instrument) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("market_type", instrument.marketType());
        node.put("base", instrument.base());
        node.put("quote", instrument.quote());
        node.put("settle", "USDT");
        return node;
    }

    private String resolveUnit(String unit, String base, String exchange) {
        return switch (unit) {
            case "base" -> base;
            case "quote" -> "USDT";
            case "ratio" -> "ratio";
            case "contract" -> "contract:" + exchange + ":" + base + "-USDT";
            default -> "percent";
        };
    }
}
