package com.cexpilot.metric;

/**
 * 单个指标请求按交易所展开后的结果：物理节点 ID、原始请求、交易所、类型化查询。
 */
public record ResolvedMetric(String physicalId, MetricRequest request, String exchange, MetricQuery query) {
}
