package com.cexpilot.metric;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 编译期补齐后的单指标节点有效查询条件（按交易所展开），带字段级来源标记（{@link ConditionOrigin}）。
 * 多对象查询按节点保存，不压成全局 symbol/exchange。仅用于持久化（QueryFrame），不参与执行。
 *
 * @param instrument 编译器规范化后的完整 instrument（market_type/base/quote/settle）
 */
public record EffectiveTarget(
        String groupId,
        String physicalId,
        String exchange,
        ConditionOrigin exchangeOrigin,
        String metric,
        String queryShape,
        JsonNode instrument,
        ConditionOrigin settleOrigin,
        JsonNode timeExpression,
        String interval,
        ConditionOrigin intervalOrigin,
        Boolean includeUnclosed,
        ConditionOrigin includeUnclosedOrigin,
        Integer count,
        Integer depth,
        ConditionOrigin depthOrigin,
        String unit,
        String provider,
        String catalogVersion) {
}
