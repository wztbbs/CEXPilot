package com.cexpilot.evidence;

import com.cexpilot.metric.ConditionOrigin;
import com.cexpilot.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 保存/加载/会话隔离/过期清理/超限不缓存/lookup_key 稳定性。 */
class EvidenceStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");

    private final InMemoryEvidenceRepository repository = new InMemoryEvidenceRepository();
    private final EvidenceStore store = new EvidenceStore(repository);

    static EffectiveTarget snapshotTarget() throws Exception {
        return new EffectiveTarget("m1", "metric_0", "binance", ConditionOrigin.EXPLICIT,
                "price.last", "snapshot",
                MAPPER.readTree("{\"market_type\":\"perpetual\",\"base\":\"BTC\",\"quote\":\"USDT\",\"settle\":\"USDT\"}"),
                ConditionOrigin.DEFAULT, null,
                null, null, null, null, null, null, null, "USDT", "ticker", "v1");
    }

    static JsonNode snapshotPayload() throws Exception {
        return MAPPER.readTree("""
                {"group_id":"m1","metric":"price.last","exchange":"binance","query_shape":"snapshot",
                 "unit":"USDT","instrument":{"market_type":"perpetual","base":"BTC","quote":"USDT","settle":"USDT"},
                 "source":{"provider":"ticker","selector":"LAST"},"catalog_version":"v1",
                 "as_of":"2026-10-12 10:00:00","estimated":false,"truncated":false,"value":83000}
                """);
    }

    @Test
    void 保存后可按会话加载并进入候选列表() throws Exception {
        var saved = store.saveFact("conv-1", "turn-1", "trace-1", snapshotTarget(), snapshotPayload(), NOW);
        assertTrue(saved.isPresent());
        EvidenceFact fact = saved.get();

        assertEquals(fact, store.loadForConversation("conv-1", fact.factId()).orElseThrow());
        assertEquals(List.of(fact.factId()),
                store.listReusable("conv-1", NOW).stream().map(EvidenceFact::factId).toList());
        // 观测时刻取 as_of；保留期 24h
        assertEquals("2026-10-12 10:00:00", fact.observedAt());
        assertEquals(NOW.plus(EvidenceStore.RETENTION), fact.retainedUntil());
        assertEquals(EvidenceStore.SCHEMA_VERSION, fact.schemaVersion());
        assertFalse(fact.derived());
    }

    @Test
    void 跨会话引用一律查不到() throws Exception {
        var saved = store.saveFact("conv-1", "turn-1", "trace-1", snapshotTarget(), snapshotPayload(), NOW)
                .orElseThrow();

        assertTrue(store.loadForConversation("conv-2", saved.factId()).isEmpty());
        assertTrue(store.listReusable("conv-2", NOW).isEmpty());
    }

    @Test
    void 过期后不进候选且可被清理() throws Exception {
        var saved = store.saveFact("conv-1", "turn-1", "trace-1", snapshotTarget(), snapshotPayload(), NOW)
                .orElseThrow();
        Instant after = NOW.plus(EvidenceStore.RETENTION).plusSeconds(1);

        assertTrue(store.listReusable("conv-1", after).isEmpty(), "过期事实不得进候选");
        // 过期但未清理时按 id 仍能加载（绑定侧再做 retained_until 断言，走 UNAVAILABLE 路径）
        assertTrue(store.loadForConversation("conv-1", saved.factId()).isPresent());

        assertEquals(1, store.deleteExpired(after));
        assertTrue(store.loadForConversation("conv-1", saved.factId()).isEmpty(), "清理后按 id 也查不到");
        assertEquals(0, store.deleteExpired(after));
    }

    @Test
    void payload超限不缓存但落库记录原因() throws Exception {
        var payload = snapshotPayload();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload)
                .put("padding", "x".repeat(EvidenceStore.MAX_PAYLOAD_BYTES));

        var saved = store.saveFact("conv-1", "turn-1", "trace-1", snapshotTarget(), payload, NOW);

        assertTrue(saved.isEmpty(), "超限事实不返回（不进候选、不可绑定）");
        assertEquals(1, repository.rows.size());
        EvidenceFact row = repository.rows.values().iterator().next();
        assertNull(row.payload());
        assertTrue(row.cacheSkipReason().startsWith("payload_too_large"), row.cacheSkipReason());
        assertTrue(store.listReusable("conv-1", NOW).isEmpty());
    }

    @Test
    void lookupKey稳定且对身份字段敏感() throws Exception {
        var a = store.saveFact("conv-1", "turn-1", "trace-1", snapshotTarget(), snapshotPayload(), NOW)
                .orElseThrow();
        var b = store.saveFact("conv-1", "turn-2", "trace-2", snapshotTarget(), snapshotPayload(), NOW)
                .orElseThrow();
        assertEquals(a.lookupKey(), b.lookupKey(), "同会话同身份的 lookup_key 稳定");
        assertEquals(64, a.lookupKey().length());

        EffectiveTarget other = new EffectiveTarget("m1", "metric_0", "okx", ConditionOrigin.EXPLICIT,
                "price.last", "snapshot", snapshotTarget().instrument(),
                ConditionOrigin.DEFAULT, null,
                null, null, null, null, null, null, null, "USDT", "ticker", "v1");
        var c = store.saveFact("conv-1", "turn-3", "trace-3", other, snapshotPayload(), NOW).orElseThrow();
        assertNotEquals(a.lookupKey(), c.lookupKey(), "身份字段（exchange）变化必须改变 lookup_key");

        var d = store.saveFact("conv-2", "turn-1", "trace-1", snapshotTarget(), snapshotPayload(), NOW)
                .orElseThrow();
        assertNotEquals(a.lookupKey(), d.lookupKey(), "lookup_key 含会话范围");
    }

    @Test
    void 派生事实标注derived且记录来源() throws Exception {
        var base = store.saveFact("conv-1", "turn-1", "trace-1", snapshotTarget(), snapshotPayload(), NOW)
                .orElseThrow();
        var derived = store.saveDerivedFact("conv-1", "turn-1", "trace-1", "avg",
                MAPPER.readTree("{\"value\":100,\"unit\":\"USDT\"}"), List.of(base.factId()), NOW)
                .orElseThrow();

        assertTrue(derived.derived());
        assertEquals(List.of(base.factId()), derived.sourceFactIds());
        assertEquals("avg", derived.identity().path("operator").asText());
        // 派生事实可进候选列表展示（Assembler 标注 derived），绑定侧负责拒绝
        assertEquals(2, store.listReusable("conv-1", NOW).size());
    }
}
