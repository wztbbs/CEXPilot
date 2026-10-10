package com.cexpilot.metric;

import java.util.List;

/**
 * 逻辑计划中的单个指标查询组：同一指标、同一组交易所、同一查询形态与参数。
 * 一个 MetricRequest 会在编译阶段按 exchanges 展开为多个取数节点。
 */
public record MetricRequest(
        String id,
        String metric,
        InstrumentSpec instrument,
        QueryShape shape,
        List<String> exchanges,
        QueryParams params) {

    public MetricRequest {
        exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
    }
}
