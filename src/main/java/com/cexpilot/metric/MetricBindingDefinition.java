package com.cexpilot.metric;

import java.util.Set;

/**
 * 指标在某一查询形态下的物理绑定定义：Provider、选择器、交易所/粒度能力、以及可选参数范围。
 * exchanges 为 null 表示不限定交易所；intervals 为 null 表示不接受粒度参数；
 * count / depth 为 null 表示该形态不使用对应参数。
 */
public record MetricBindingDefinition(
        String provider,
        String selector,
        Set<String> exchanges,
        Set<String> intervals,
        BoundInteger count,
        BoundInteger depth) {
    public MetricBindingDefinition {
        if (exchanges != null) exchanges = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(exchanges));
        if (intervals != null) intervals = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(intervals));
    }

    public boolean supportsExchange(String exchange) {
        return exchanges == null || exchanges.contains(exchange);
    }

    public boolean supportsInterval(String interval) {
        return intervals != null && intervals.contains(interval);
    }

    public boolean hasIntervalSupport() {
        return intervals != null;
    }
}
