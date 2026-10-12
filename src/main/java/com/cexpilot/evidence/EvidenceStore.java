package com.cexpilot.evidence;

import com.cexpilot.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FACT 事实的组装、保存与加载。保存时机在执行后、Answer 前（DagRuntime），
 * 即使 Answer 失败事实也已落库（F13：失败后重试可复用已取事实）。
 *
 * 复用安全边界：
 * - loadForConversation 必须带会话范围，跨会话引用一律查不到；
 * - payload 单条上限 {@link #MAX_PAYLOAD_BYTES}，超限行仍落库（audit）但 payload 为空、
 *   metadata 记 cache_skip_reason，不进候选列表、不可绑定；
 * - retained_until = 保存时 + {@link #RETENTION}，过期由 deleteExpired 清理。
 */
@Component
public class EvidenceStore {

    private static final Logger log = LoggerFactory.getLogger(EvidenceStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    public static final Duration RETENTION = Duration.ofHours(24);
    /** payload 结构版本；与节点 catalogVersion 一起构成绑定的版本门槛。 */
    public static final String SCHEMA_VERSION = "1";

    private final EvidenceRepository repository;

    public EvidenceStore(EvidenceRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存一个原始取数事实（metric 节点）。payload 为该节点 MetricResultJson 输出（含 identity+data）。
     * 超限/不可序列化时返回 empty（不进候选、不可绑定），但行仍落库并记录原因。
     */
    public Optional<EvidenceFact> saveFact(String conversationId, String turnId, String traceId,
                                           EffectiveTarget target, JsonNode payload, Instant now) {
        ObjectNode identity = identity(target, payload);
        String observedAt = payload != null && payload.has("as_of") ? payload.get("as_of").asText() : null;
        return persist(newFact(conversationId, turnId, traceId, false, List.of(),
                identity, observedAt, qualityOf(payload), payload, now));
    }

    /**
     * 保存一个派生事实（算子结果）。derived=true，仅作审计与答案来源说明，
     * 不进样本集候选（Assembler 标注、Binder 拒绝绑定）。
     */
    public Optional<EvidenceFact> saveDerivedFact(String conversationId, String turnId, String traceId,
                                                  String operator, JsonNode payload,
                                                  List<String> sourceFactIds, Instant now) {
        ObjectNode identity = MAPPER.createObjectNode();
        identity.put("derived", true).put("operator", operator);
        if (payload != null && payload.has("unit")) identity.put("unit", payload.get("unit").asText());
        return persist(newFact(conversationId, turnId, traceId, true, sourceFactIds,
                identity, null, null, payload, now));
    }

    /** 必须同时校验会话范围：conversationId 不匹配的 factId 视同不存在。 */
    public Optional<EvidenceFact> loadForConversation(String conversationId, String factId) {
        return repository.find(conversationId, factId);
    }

    /** 未过期且有 payload 的候选，按取数时间倒序（短别名按此顺序稳定分配）。 */
    public List<EvidenceFact> listReusable(String conversationId, Instant now) {
        return repository.listReusable(conversationId, now);
    }

    public int deleteExpired(Instant now) {
        return repository.deleteExpired(now);
    }

    private EvidenceFact newFact(String conversationId, String turnId, String traceId,
                                 boolean derived, List<String> sourceFactIds,
                                 ObjectNode identity, String observedAt, ObjectNode quality,
                                 JsonNode payload, Instant now) {
        return new EvidenceFact(UUID.randomUUID().toString(), conversationId, turnId, traceId,
                derived, sourceFactIds, identity, observedAt, quality,
                lookupKey(conversationId, identity),
                payload == null ? null : payload.deepCopy(), null, now, now.plus(RETENTION), SCHEMA_VERSION);
    }

    private Optional<EvidenceFact> persist(EvidenceFact fact) {
        if (fact.payload() != null) {
            int bytes;
            try {
                bytes = fact.payload().toString().getBytes(StandardCharsets.UTF_8).length;
            } catch (Exception e) {
                log.warn("事实 payload 序列化失败，不缓存 factId={}: {}", fact.factId(), e.getMessage());
                repository.insert(factWithoutPayload(fact, "payload_not_serializable"));
                return Optional.empty();
            }
            if (bytes > MAX_PAYLOAD_BYTES) {
                log.info("事实 payload 超限（{}B > {}B），不缓存 factId={}", bytes, MAX_PAYLOAD_BYTES, fact.factId());
                repository.insert(factWithoutPayload(fact, "payload_too_large:" + bytes));
                return Optional.empty();
            }
        }
        repository.insert(fact);
        return Optional.of(fact);
    }

    private EvidenceFact factWithoutPayload(EvidenceFact fact, String reason) {
        return new EvidenceFact(fact.factId(), fact.conversationId(), fact.sourceTurnId(), fact.sourceTraceId(),
                fact.derived(), fact.sourceFactIds(), fact.identity(), fact.observedAt(), fact.quality(),
                fact.lookupKey(), null, reason, fact.fetchedAt(), fact.retainedUntil(), fact.schemaVersion());
    }

    /**
     * 规范化身份：固定字段顺序、只放非空字段；时间区间用 payload 的 requested_range 原文
     * （绝对区间 + 时区，渲染口径与绑定侧 TimeRange 解析结果一致）。观测时刻不进身份——
     * 快照复用的意义正是保留旧 as_of。
     */
    public static ObjectNode identity(EffectiveTarget target, JsonNode payload) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("metric", target.metric())
                .put("exchange", target.exchange())
                .put("query_shape", target.queryShape())
                .put("unit", target.unit())
                .put("provider", target.provider())
                .put("catalog_version", target.catalogVersion());
        out.set("instrument", target.instrument().deepCopy());
        if (target.interval() != null) out.put("interval", target.interval());
        if (target.includeUnclosed() != null) out.put("include_unclosed", target.includeUnclosed());
        if (target.count() != null) out.put("count", target.count());
        if (target.depth() != null) out.put("depth", target.depth());
        if (payload != null && payload.has("requested_range")) {
            out.set("requested_range", payload.get("requested_range").deepCopy());
        }
        return out;
    }

    private static ObjectNode qualityOf(JsonNode payload) {
        if (payload == null) return null;
        ObjectNode quality = MAPPER.createObjectNode();
        if (payload.has("sample_complete")) quality.put("sample_complete", payload.get("sample_complete").asBoolean());
        if (payload.has("coverage")) {
            JsonNode coverage = payload.get("coverage");
            quality.put("range_complete", coverage.path("range_complete").asBoolean());
            quality.put("contains_unclosed", coverage.path("contains_unclosed").asBoolean());
        }
        return quality.isEmpty() ? null : quality;
    }

    /** lookup_key = SHA-256(规范化身份串)：会话 + 身份 JSON（字段顺序固定）的 hex。 */
    public static String lookupKey(String conversationId, ObjectNode identity) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest((conversationId + "|" + identity).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("lookup_key 计算失败", e);
        }
    }
}
