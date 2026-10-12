package com.cexpilot.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * 一条会话级取数事实（conversation_artifact 行，kind='FACT'）。
 *
 * @param identity       规范化身份（metric/exchange/shape/instrument/unit/provider/catalogVersion/
 *                       interval/includeUnclosed/count/depth/requested_range），绑定校验按它逐字段比对
 * @param observedAt     观测时刻渲染值（快照取 as_of；序列为 null，窗口以 identity.requested_range 表达）
 * @param quality        质量摘要（sample_complete / coverage 等，来自 payload），可空
 * @param payload        完整 MetricResultJson 输出；超限未缓存时为 null（cacheSkipReason 有值）
 * @param sourceFactIds  派生事实的来源 factId 列表（derived=true 时非空）
 */
public record EvidenceFact(
        String factId,
        String conversationId,
        String sourceTurnId,
        String sourceTraceId,
        boolean derived,
        List<String> sourceFactIds,
        ObjectNode identity,
        String observedAt,
        ObjectNode quality,
        String lookupKey,
        JsonNode payload,
        String cacheSkipReason,
        Instant fetchedAt,
        Instant retainedUntil,
        String schemaVersion) {

    public EvidenceFact {
        sourceFactIds = sourceFactIds == null ? List.of() : List.copyOf(sourceFactIds);
    }
}
