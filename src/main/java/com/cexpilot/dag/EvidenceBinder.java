package com.cexpilot.dag;

import com.cexpilot.evidence.EvidenceAssembler;
import com.cexpilot.evidence.EvidenceFact;
import com.cexpilot.evidence.EvidenceStore;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.cexpilot.metric.ExecutionBindings;
import com.cexpilot.metric.LogicalPlan;
import com.cexpilot.metric.MetricRequest;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 计划校验通过后、执行前生成 {@link ExecutionBindings}（服务端职责）。
 *
 * 绑定校验（任一不符即整个计划进修复/澄清，不允许部分冒充）：
 * evidence_ref 必须在候选短别名集合内；fact 必须属于本会话、未过期、未清理、非派生；
 * 规范化身份（metric/exchange/shape/instrument/unit/provider/count/depth/interval/
 * includeUnclosed/绝对区间）与 catalogVersion、schemaVersion 必须逐项一致——
 * 禁止用口径不同的旧快照或当前值冒充（F21）。
 */
public class EvidenceBinder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvidenceStore store;

    public EvidenceBinder(EvidenceStore store) {
        this.store = store;
    }

    public record BindResult(ExecutionBindings bindings, String error) {
        static BindResult ok(ExecutionBindings bindings) { return new BindResult(bindings, null); }
        static BindResult error(String error) { return new BindResult(null, error); }
    }

    private record FactCheck(EvidenceFact fact, String error) {
    }

    /**
     * 协议层校验（不看库存）：pinned_result 的 evidence_ref 必须在候选别名集合内。
     * 模型只能给短别名，factId 永远由服务端解析，不接受模型给的任何 id。
     */
    public String checkProtocol(LogicalPlan plan, List<EvidenceAssembler.Entry> candidates) {
        Map<String, EvidenceAssembler.Entry> byRef = new LinkedHashMap<>();
        for (EvidenceAssembler.Entry entry : candidates) byRef.put(entry.ref(), entry);
        for (MetricRequest metric : plan.metrics()) {
            if (!"pinned_result".equals(metric.referenceMode())) continue;
            String ref = metric.evidenceRef();
            if (ref == null || !byRef.containsKey(ref)) {
                return "指标组 " + metric.id() + " 的 evidence_ref \"" + ref + "\" 不在本轮【可引用历史事实】列表"
                        + (byRef.isEmpty() ? "（列表为空，无可绑定快照）" : "（可选: " + String.join(", ", byRef.keySet()) + "）")
                        + "；请从列表选择 ref，或改用 reference_mode=\"latest\" 重新取数";
            }
        }
        return null;
    }

    /**
     * 实体绑定：逐 pinned 组解析 ref → factId → 会话内加载 → 身份/版本/有效期校验。
     * 每个决策（命中或拒绝）都落 EVIDENCE_REUSE_DECISION trace 事件。
     */
    public BindResult bind(CompiledPlan compiled, List<EvidenceAssembler.Entry> candidates,
                           String conversationId, RequestContext requestContext,
                           String traceId, TraceSink sink) {
        Map<String, EvidenceAssembler.Entry> byRef = new LinkedHashMap<>();
        for (EvidenceAssembler.Entry entry : candidates) byRef.put(entry.ref(), entry);
        Map<String, EffectiveTarget> targets = compiled.targets().stream()
                .collect(Collectors.toMap(EffectiveTarget::physicalId, t -> t, (a, b) -> a, LinkedHashMap::new));
        Map<String, ExecutionBindings.ReuseFact> reused = new LinkedHashMap<>();
        for (MetricRequest metric : compiled.logicalPlan().metrics()) {
            if (!"pinned_result".equals(metric.referenceMode())) continue;
            EvidenceAssembler.Entry entry = byRef.get(metric.evidenceRef());
            if (entry == null) {
                return BindResult.error("evidence_ref 未通过协议校验: " + metric.evidenceRef());
            }
            FactCheck check = loadAndCheckFact(conversationId, entry.fact().factId(), requestContext);
            EvidenceFact fact = check.fact();
            String reject = check.error();
            // pinned 组只允许单交易所单分支：一条事实覆盖一个交易所，多交易所必须拆开分别绑定
            String physicalId = null;
            EffectiveTarget target = null;
            if (reject == null) {
                if (metric.exchanges().size() != 1) {
                    reject = "pinned_result 绑定的指标组必须只含一个交易所（一条历史事实只覆盖一个交易所）；"
                            + "多交易所请拆成多个指标组分别绑定或取数";
                } else {
                    physicalId = compiled.branches().getOrDefault(metric.id(), Map.of())
                            .get(metric.exchanges().get(0));
                    target = targets.get(physicalId);
                    if (target == null) {
                        reject = "编译产物缺少 pinned 节点的有效条件: " + metric.id();
                    }
                }
            }
            if (reject == null) reject = checkIdentity(fact, target, requestContext);
            if (reject != null) {
                recordDecision(sink, traceId, metric.id(), physicalId, metric,
                        entry.fact(), "REJECT", reject);
                return BindResult.error("历史事实 " + metric.evidenceRef() + " 不可复用: " + reject
                        + "；请修正引用，或改用 reference_mode=\"latest\" 重新取数");
            }
            recordDecision(sink, traceId, metric.id(), physicalId, metric, fact, "REUSE", null);
            reused.put(physicalId, new ExecutionBindings.ReuseFact(fact.factId(), fact.sourceTurnId(),
                    fact.observedAt(), fact.fetchedAt().toString(), fact.payload().deepCopy()));
        }
        return BindResult.ok(new ExecutionBindings(reused));
    }

    /** 会话范围 / 派生 / 版本 / 有效期：与具体查询条件无关的通用断言；通过时带出库存事实本体。 */
    private FactCheck loadAndCheckFact(String conversationId, String factId, RequestContext requestContext) {
        var stored = store.loadForConversation(conversationId, factId);
        if (stored.isEmpty()) {
            return new FactCheck(null, "原快照不存在、已被清理或不属于本会话");
        }
        EvidenceFact fact = stored.get();
        if (fact.derived()) {
            return new FactCheck(null,
                    "派生计算结果（" + fact.identity().path("operator").asText() + "）不能作为样本集来源，"
                            + "如需对原始样本计算请绑定原始取数事实");
        }
        if (!EvidenceStore.SCHEMA_VERSION.equals(fact.schemaVersion())) {
            return new FactCheck(null, "快照结构版本不符（schema_version=" + fact.schemaVersion() + "）");
        }
        if (!fact.retainedUntil().isAfter(requestContext.requestTime())) {
            return new FactCheck(null, "原快照已过保留期（retained_until=" + fact.retainedUntil() + "）");
        }
        return new FactCheck(fact, null);
    }

    /** 规范化身份逐项比对；时间类形态把本轮 TimeSpec 按请求基准消解成绝对区间后比对。 */
    private String checkIdentity(EvidenceFact fact, EffectiveTarget target, RequestContext requestContext) {
        ObjectNode id = fact.identity();
        if (!target.metric().equals(id.path("metric").asText())) return "指标不符";
        if (!target.exchange().equals(id.path("exchange").asText())) return "交易所不符";
        if (!target.queryShape().equals(id.path("query_shape").asText())) return "查询形态不符";
        if (!target.unit().equals(id.path("unit").asText())) return "单位不符";
        if (!target.provider().equals(id.path("provider").asText())) return "provider 不符";
        if (!target.catalogVersion().equals(id.path("catalog_version").asText(null))) {
            return "指标目录版本不符，不能静默复用旧口径快照";
        }
        if (!target.instrument().equals(id.path("instrument"))) return "instrument 不符";
        if (!Objects.equals(target.interval(), id.has("interval") ? id.get("interval").asText() : null)) {
            return "K 线粒度不符";
        }
        if (!Objects.equals(target.includeUnclosed(),
                id.has("include_unclosed") ? id.get("include_unclosed").asBoolean() : null)) {
            return "include_unclosed 口径不符";
        }
        if (!Objects.equals(target.count(), id.has("count") ? id.get("count").asInt() : null)) return "期数不符";
        if (!Objects.equals(target.depth(), id.has("depth") ? id.get("depth").asInt() : null)) return "档位数不符";
        if (target.timeExpression() != null) {
            JsonNode expectedRange;
            try {
                TimeRange range = new TimeRangeResolver(
                        Clock.fixed(requestContext.requestTime(), requestContext.userZone()))
                        .resolve(requestContext.userZone(),
                                TimeSpecParser.parse(target.timeExpression()), requestContext.requestTime());
                expectedRange = rangeJson(range);
            } catch (Exception e) {
                return "本轮时间条件无法消解为绝对区间: " + e.getMessage();
            }
            if (!expectedRange.equals(id.path("requested_range"))) {
                return "绝对区间不符（pinned 钉住的是原窗口，时间条件变化后不能复用旧快照）";
            }
        }
        return null;
    }

    /** 与 MetricResultJson 渲染 requested_range 相同的结构与字段顺序，保证身份比对按 JSON 等值成立。 */
    static ObjectNode rangeJson(TimeRange range) {
        return MAPPER.createObjectNode()
                .put("start_inclusive", com.cexpilot.market.Times.readable(range.startInclusive().toEpochMilli(), range.timezone()))
                .put("end_exclusive", com.cexpilot.market.Times.readable(range.endExclusive().toEpochMilli(), range.timezone()))
                .put("timezone", range.timezone().getId());
    }

    private static void recordDecision(TraceSink sink, String traceId, String groupId, String physicalId,
                                       MetricRequest metric, EvidenceFact fact, String decision, String reason) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("node_id", physicalId == null ? groupId : physicalId);
        input.put("evidence_ref", metric.evidenceRef());
        if (fact != null) input.put("fact_id", fact.factId());
        input.put("reference_mode", metric.referenceMode());
        input.put("decision", decision);
        if (reason != null) input.put("reason", reason);
        if (fact != null && "REUSE".equals(decision)) {
            input.put("source_turn_id", fact.sourceTurnId());
            if (fact.observedAt() != null) input.put("observed_at", fact.observedAt());
            input.put("fetched_at", fact.fetchedAt().toString());
        }
        sink.record(new TraceEvent(traceId, "EVIDENCE_REUSE_DECISION", "evidence_binder",
                input.toString(), null, null, null, null, null, null,
                "REJECT".equals(decision) ? reason : null));
    }
}
