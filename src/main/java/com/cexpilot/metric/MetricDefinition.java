package com.cexpilot.metric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 同一指标按市场、查询形态绑定能力，不将不同市场的交易所限制取并集。 */
public record MetricDefinition(String description, String unit,
                               Map<String, Map<QueryShape, MetricBindingDefinition>> marketBindings) {
    public MetricDefinition {
        var copy = new LinkedHashMap<String, Map<QueryShape, MetricBindingDefinition>>();
        marketBindings.forEach((market, bindings) -> copy.put(market,
                Collections.unmodifiableMap(new LinkedHashMap<>(bindings))));
        marketBindings = Collections.unmodifiableMap(copy);
    }
    public Map<QueryShape, MetricBindingDefinition> bindings(String market) {
        return marketBindings.getOrDefault(market, Map.of());
    }
    /** 兼容永续调用方；实际路由必须显式传入市场。 */
    public Map<QueryShape, MetricBindingDefinition> bindings() { return bindings("perpetual"); }
}
