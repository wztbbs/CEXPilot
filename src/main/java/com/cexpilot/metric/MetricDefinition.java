package com.cexpilot.metric;

import java.util.Map;

/**
 * 指标目录中的指标定义：业务口径 + 按查询形态划分的物理绑定。
 */
public record MetricDefinition(String description, String unit,
                               Map<QueryShape, MetricBindingDefinition> bindings) {
    public MetricDefinition {
        bindings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bindings));
    }
}
