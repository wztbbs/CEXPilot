package com.cexpilot.metric;

import java.util.List;

/**
 * 逻辑计划中的单个指标查询组：同一指标、同一组交易所、同一查询形态与参数。
 * 一个 MetricRequest 会在编译阶段按 exchanges 展开为多个取数节点。
 *
 * @param referenceMode "pinned_result" 绑定会话内已验证历史快照（需 evidenceRef）；
 *                      "latest" 或 null 重新取数（默认）
 * @param evidenceRef   【可引用历史事实】中的短别名（fact_N）；仅 pinned_result 时有效。
 *                      模型只能给短别名，factId 由服务端在绑定阶段解析校验
 */
public record MetricRequest(
        String id,
        String metric,
        InstrumentSpec instrument,
        QueryShape shape,
        List<String> exchanges,
        QueryParams params,
        String referenceMode,
        String evidenceRef) {

    public MetricRequest(String id, String metric, InstrumentSpec instrument, QueryShape shape,
                         List<String> exchanges, QueryParams params) {
        this(id, metric, instrument, shape, exchanges, params, null, null);
    }

    public MetricRequest {
        exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
    }
}
