package com.cexpilot.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 编译器生成的可信 Provider 绑定；不再描述旧 Tool 的 JSON 字段和列。 */
public record MetricBinding(String groupId, String metric, String exchange, String shape,
                            JsonNode instrument, String unit, String provider,
                            MetricSelector selector, String catalogVersion) {
    public MetricBinding {
        instrument = instrument.deepCopy();
        if (provider == null || provider.isBlank() || selector == null || !selector.supports(shape)) {
            throw new IllegalArgumentException("不可用的指标 Provider 绑定: " + provider + "/" + selector + "/" + shape);
        }
    }
    public ObjectNode identity() { return MetricResultJson.identity(this); }
    public JsonNode outputSchema() { return MetricResultJson.outputSchema(this); }
    public boolean isRangeStatistic() { return QueryShape.RANGE_STATISTIC == QueryShape.from(shape); }
}
