package com.cexpilot.evidence;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用内存版 EvidenceRepository：语义与 SQL 版一致（会话范围过滤、未过期且有 payload 才可复用、
 * fetched_at 倒序），不验证 SQL 本身（与 ConversationTurnConcurrencyTest 的内存模拟约定相同）。
 */
public class InMemoryEvidenceRepository extends EvidenceRepository {

    public final Map<String, EvidenceFact> rows = new LinkedHashMap<>();

    public InMemoryEvidenceRepository() {
        super(null);
    }

    @Override
    public void insert(EvidenceFact fact) {
        rows.put(fact.factId(), fact);
    }

    @Override
    public Optional<EvidenceFact> find(String conversationId, String factId) {
        EvidenceFact fact = rows.get(factId);
        return fact != null && fact.conversationId().equals(conversationId)
                ? Optional.of(fact) : Optional.empty();
    }

    @Override
    public List<EvidenceFact> listReusable(String conversationId, Instant now) {
        return rows.values().stream()
                .filter(f -> f.conversationId().equals(conversationId))
                .filter(f -> f.payload() != null && f.retainedUntil().isAfter(now))
                .sorted(Comparator.comparing(EvidenceFact::fetchedAt).reversed()
                        .thenComparing(EvidenceFact::factId, Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public int deleteExpired(Instant now) {
        List<String> expired = rows.values().stream()
                .filter(f -> !f.retainedUntil().isAfter(now))
                .map(EvidenceFact::factId).toList();
        expired.forEach(rows::remove);
        return expired.size();
    }
}
