package com.cexpilot.evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * 把会话内可复用 FACT 渲染成 Planner 候选索引（availableEvidence）。
 * 只给短别名与身份/质量摘要，不含 payload 明细；ref（fact_1/fact_2…）按列表顺序稳定分配。
 */
public final class EvidenceAssembler {

    private EvidenceAssembler() {
    }

    /** 一条候选：Planner 可见的短别名 + 服务端事实本体（模型只能输出别名，factId 由服务端解析）。 */
    public record Entry(String ref, EvidenceFact fact) {
    }

    /** 按传入顺序（调用方保证稳定，如 fetchedAt 倒序）分配 fact_1..fact_n。 */
    public static List<Entry> assignRefs(List<EvidenceFact> facts) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            entries.add(new Entry("fact_" + (i + 1), facts.get(i)));
        }
        return entries;
    }

    /** Planner context 的【可引用历史事实】段；无候选时返回空串（不占预算）。 */
    public static String render(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【可引用历史事实】（程序验证过的历史取数结果，可按需绑定复用；明细数据不在此展示）\n");
        for (Entry entry : entries) {
            sb.append("- ref=").append(entry.ref()).append(' ').append(describe(entry.fact())).append('\n');
        }
        return sb.toString();
    }

    static String describe(EvidenceFact fact) {
        StringBuilder s = new StringBuilder();
        var id = fact.identity();
        if (fact.derived()) {
            s.append("derived=true（派生计算结果 operator=").append(id.path("operator").asText())
                    .append("，不可作为样本集来源绑定）");
        } else {
            s.append("metric=").append(id.path("metric").asText())
                    .append("，shape=").append(id.path("query_shape").asText())
                    .append("，exchange=").append(id.path("exchange").asText())
                    .append("，instrument=").append(id.path("instrument").toString());
            if (id.has("requested_range")) {
                s.append("，range=").append(id.get("requested_range"));
            }
            if (id.has("count")) {
                s.append("，count=").append(id.get("count").asInt());
            }
            if (fact.observedAt() != null) {
                s.append("，observed_at=").append(fact.observedAt());
            }
            s.append("，fetched_at=").append(fact.fetchedAt());
            if (fact.quality() != null) {
                s.append("，quality=").append(fact.quality());
            }
        }
        return s.toString();
    }
}
