package com.cexpilot.dag;

import com.cexpilot.evidence.EvidenceAssembler;
import com.cexpilot.evidence.EvidenceFact;
import com.cexpilot.evidence.EvidenceStore;
import com.cexpilot.metric.CalculationRequest;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 比较类算子的 (新值槽位, 基准/旧值槽位)：基准侧必须放 pinned 旧快照。 */
    private static final Map<String, String[]> COMPARISON_SLOTS = Map.of(
            "relative_change", new String[]{"current", "baseline"},
            "compare", new String[]{"left", "right"},
            "difference", new String[]{"left", "right"},
            "ratio", new String[]{"left", "right"});

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
     *
     * <p>修复话术不得建议"改用 latest"（R4）：原快照不可用时唯一合法出路是
     * needs_clarification 交还用户决定，否则模型会顺着提示降级，"刚才那个价格"被偷换成现价。
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
                        + "；请从列表选择正确的 ref。禁止改用 reference_mode=\"latest\" 或删除该组的历史引用来绕过，"
                        + "原快照确实不可用时必须输出 needs_clarification=true 说明，由用户决定是否按最新数据重查";
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
                        + "；若 ref 选错请修正引用重试。禁止改用 reference_mode=\"latest\" 或删除该组的历史引用来绕过，"
                        + "原快照确实不可用时必须输出 needs_clarification=true 说明，由用户决定是否按最新数据重查");
            }
            recordDecision(sink, traceId, metric.id(), physicalId, metric, fact, "REUSE", null);
            reused.put(physicalId, new ExecutionBindings.ReuseFact(fact.factId(), fact.sourceTurnId(),
                    fact.observedAt(), fact.fetchedAt().toString(), fact.payload().deepCopy()));
        }
        return BindResult.ok(new ExecutionBindings(reused));
    }

    /**
     * 比较类算子的来源核对（R5 改造）：只产出非阻断的提示信息，不再做方向校验、不进规划修复。
     *
     * <p>方向断言已删除："现在比刚才涨了多少"要求旧值在 baseline，但"用刚才的价格减去现在的价格"
     * "刚才是现在的几倍"等合法问题的方向由本轮语义决定，不能从快照新旧推导统一方向并强制交换——
     * 旧值在 current 侧曾被判"方向接反"打回，模型照改后符号/分母就错了。
     * 比较方向的正确性由 prompt 规则（比较必须明确方向与基准）与 Answer 侧来源标注承担。
     *
     * <p>数值字面量恰好等于某条历史快照值只是线索而非证据（用户可能明确给出该数字），
     * 降级为提示信息：调用方落审计事件，Answer/回放侧可参考，不再拦截计划。
     */
    public List<String> checkComparisonWarnings(CompiledPlan compiled, ExecutionBindings bindings,
                                                List<EvidenceAssembler.Entry> candidates) {
        List<String> warnings = new java.util.ArrayList<>();
        for (CalculationRequest calc : compiled.logicalPlan().calculations()) {
            String[] slots = COMPARISON_SLOTS.get(calc.operator());
            if (slots == null) continue;
            JsonNode fresh = calc.input().path(slots[0]);
            JsonNode base = calc.input().path(slots[1]);
            String literalWarning = literalImpersonation(calc, slots[0], fresh, candidates);
            if (literalWarning == null) literalWarning = literalImpersonation(calc, slots[1], base, candidates);
            if (literalWarning != null) warnings.add(literalWarning);
        }
        return warnings;
    }

    private static String literalImpersonation(CalculationRequest calc, String slot, JsonNode node,
                                               List<EvidenceAssembler.Entry> candidates) {
        BigDecimal literal = numericLiteral(node);
        if (literal == null) return null;
        for (EvidenceAssembler.Entry entry : candidates) {
            EvidenceFact fact = entry.fact();
            if (fact == null || fact.derived() || fact.payload() == null) continue;
            JsonNode value = fact.payload().path("value");
            if (!value.isNumber()) continue;
            if (value.decimalValue().compareTo(literal) == 0) {
                return "calculations[" + calc.id() + "] " + calc.operator() + " 的 " + slot
                        + " 直接填了数值字面量 " + literal.toPlainString()
                        + "，与【可引用历史事实】" + entry.ref() + " 的快照值一致，疑似把历史文本数字当比较输入："
                        + "请为该历史快照建 reference_mode=\"pinned_result\" + evidence_ref=\"" + entry.ref()
                        + "\" 的指标组，并以 {{...}} 引用它作为基准侧输入，不要用字面量";
            }
        }
        return null;
    }

    /** 槽位上的数值字面量；{{...}} 引用或非数值文本返回 null。 */
    private static BigDecimal numericLiteral(JsonNode node) {
        if (node == null) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.isEmpty() || text.contains("{{")) return null;
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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
