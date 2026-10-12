package com.cexpilot.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** conversation_artifact 的 SQL 存取；组装与校验逻辑在 {@link EvidenceStore}。 */
@Repository
public class EvidenceRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(EvidenceFact fact) {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.set("identity", fact.identity());
        if (fact.observedAt() != null) metadata.put("observed_at", fact.observedAt());
        if (fact.quality() != null) metadata.set("quality", fact.quality());
        if (!fact.sourceFactIds().isEmpty()) metadata.set("source_fact_ids",
                MAPPER.valueToTree(fact.sourceFactIds()));
        if (fact.cacheSkipReason() != null) metadata.put("cache_skip_reason", fact.cacheSkipReason());
        jdbc.update("""
                        INSERT INTO conversation_artifact
                            (artifact_id, conversation_id, kind, source_turn_id, source_trace_id,
                             lookup_key, schema_version, derived, metadata_json, payload_json,
                             fetched_at, retained_until)
                        VALUES (?, ?, 'FACT', ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?)
                        """,
                fact.factId(), fact.conversationId(), fact.sourceTurnId(), fact.sourceTraceId(),
                fact.lookupKey(), fact.schemaVersion(), fact.derived() ? 1 : 0,
                metadata.toString(), fact.payload() == null ? null : fact.payload().toString(),
                Timestamp.from(fact.fetchedAt()), Timestamp.from(fact.retainedUntil()));
    }

    /** 按会话 + factId 加载；会话不匹配时返回空（防跨会话引用，通用断言）。 */
    public Optional<EvidenceFact> find(String conversationId, String factId) {
        List<EvidenceFact> rows = jdbc.query("""
                        SELECT * FROM conversation_artifact
                        WHERE conversation_id = ? AND artifact_id = ? AND kind = 'FACT'
                        """,
                (rs, rowNum) -> map(rs), conversationId, factId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 可复用候选：未过期且有缓存 payload，按取数时间倒序（最新的在前，短别名按此顺序稳定分配）。 */
    public List<EvidenceFact> listReusable(String conversationId, Instant now) {
        return jdbc.query("""
                        SELECT * FROM conversation_artifact
                        WHERE conversation_id = ? AND kind = 'FACT'
                          AND payload_json IS NOT NULL AND retained_until > ?
                        ORDER BY fetched_at DESC, artifact_id DESC
                        """,
                (rs, rowNum) -> map(rs), conversationId, Timestamp.from(now));
    }

    /** 过期清理；清理后引用走 UNAVAILABLE（"原快照不可恢复"澄清）路径。 */
    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM conversation_artifact WHERE retained_until <= ?",
                Timestamp.from(now));
    }

    private static EvidenceFact map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            ObjectNode metadata = (ObjectNode) MAPPER.readTree(rs.getString("metadata_json"));
            String payloadText = rs.getString("payload_json");
            return new EvidenceFact(
                    rs.getString("artifact_id"),
                    rs.getString("conversation_id"),
                    rs.getString("source_turn_id"),
                    rs.getString("source_trace_id"),
                    rs.getBoolean("derived"),
                    metadata.has("source_fact_ids")
                            ? Arrays.stream(MAPPER.convertValue(metadata.get("source_fact_ids"), String[].class)).toList()
                            : List.of(),
                    (ObjectNode) metadata.get("identity"),
                    metadata.has("observed_at") ? metadata.get("observed_at").asText() : null,
                    metadata.has("quality") ? (ObjectNode) metadata.get("quality") : null,
                    rs.getString("lookup_key"),
                    payloadText == null ? null : MAPPER.readTree(payloadText),
                    metadata.has("cache_skip_reason") ? metadata.get("cache_skip_reason").asText() : null,
                    rs.getTimestamp("fetched_at").toInstant(),
                    rs.getTimestamp("retained_until").toInstant(),
                    rs.getString("schema_version"));
        } catch (Exception e) {
            throw new IllegalStateException("conversation_artifact 行反序列化失败: " + e.getMessage(), e);
        }
    }

}
