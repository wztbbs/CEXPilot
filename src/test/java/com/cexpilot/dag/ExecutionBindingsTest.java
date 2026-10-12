package com.cexpilot.dag;

import com.cexpilot.evidence.EvidenceAssembler;
import com.cexpilot.evidence.EvidenceFact;
import com.cexpilot.evidence.EvidenceStore;
import com.cexpilot.evidence.InMemoryEvidenceRepository;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.cexpilot.metric.MetricCatalog;
import com.cexpilot.metric.MetricPlanCompiler;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.TraceEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.cexpilot.dag.MetricTestSupport.JSON;
import static com.cexpilot.dag.MetricTestSupport.catalogProviders;
import static com.cexpilot.dag.MetricTestSupport.registry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExecutionBindings 生成（EvidenceBinder）：pinned 各失败分支（ref 不存在/身份不符/版本不符/
 * 已清理/已过期/派生拒绝）、latest 强制 FETCH、命中时落 REUSE 决策事件。
 */
class ExecutionBindingsTest {

    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");
    private static final RequestContext CTX = new RequestContext(ZoneId.of("Asia/Shanghai"), NOW);
    private static final String ABS_RANGE =
            "{\"type\":\"absolute_range\",\"start\":{\"year\":2026,\"month\":10,\"day\":10},"
                    + "\"end\":{\"year\":2026,\"month\":10,\"day\":11},\"end_mode\":\"inclusive_date\"}";

    private final InMemoryEvidenceRepository repository = new InMemoryEvidenceRepository();
    private final EvidenceStore store = new EvidenceStore(repository);
    private final EvidenceBinder binder = new EvidenceBinder(store);
    private final List<TraceEvent> events = new ArrayList<>();

    private static ObjectNode metricGroup(String id, String metric, String shape, String exchange) {
        ObjectNode node = JSON.createObjectNode().put("id", id).put("metric", metric)
                .put("query_shape", shape);
        node.putArray("exchanges").add(exchange);
        node.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        return node;
    }

    private static CompiledPlan compile(ObjectNode... groups) {
        ObjectNode plan = JSON.createObjectNode();
        var metrics = plan.putArray("metrics");
        for (ObjectNode group : groups) metrics.add(group);
        plan.putArray("calculations");
        return compilePlan(plan);
    }

    private static CompiledPlan compilePlan(ObjectNode plan) {
        return new MetricPlanCompiler(new MetricCatalog(MetricTestSupport.LOADER), registry(), catalogProviders())
                .compileFull(plan, 8);
    }

    /** 旧快照 pinned（m1）+ 本轮新取数（m2）+ relative_change；swapped=true 时方向接反。 */
    private static ObjectNode relativeChangePlan(boolean swapped) {
        ObjectNode m1 = metricGroup("m1", "price.last", "snapshot", "binance");
        m1.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");
        ObjectNode plan = JSON.createObjectNode();
        var metrics = plan.putArray("metrics");
        metrics.add(m1);
        metrics.add(metricGroup("m2", "price.last", "snapshot", "binance"));
        ObjectNode c1 = JSON.createObjectNode().put("id", "c1").put("operator", "relative_change");
        ObjectNode input = c1.putObject("input");
        input.put("current", swapped ? "{{m1.binance.value}}" : "{{m2.binance.value}}");
        input.put("baseline", swapped ? "{{m2.binance.value}}" : "{{m1.binance.value}}");
        plan.putArray("calculations").add(c1);
        return plan;
    }

    private static ObjectNode payloadFor(EffectiveTarget target, double value) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("metric", target.metric()).put("exchange", target.exchange())
                .put("query_shape", target.queryShape()).put("unit", target.unit())
                .put("catalog_version", target.catalogVersion());
        payload.set("instrument", target.instrument().deepCopy());
        payload.putObject("source").put("provider", target.provider()).put("selector", "LAST");
        payload.put("as_of", "2026-10-12 09:00:00").put("value", value);
        return payload;
    }

    /** 库存入一条 price.last 快照事实并返回候选列表（ref=fact_1）。 */
    private List<EvidenceAssembler.Entry> seedSnapshotFact() {
        CompiledPlan reference = compile(metricGroup("m1", "price.last", "snapshot", "binance"));
        EffectiveTarget target = reference.targets().get(0);
        store.saveFact("conv-1", "turn-1", "trace-1", target, payloadFor(target, 83000), NOW);
        return EvidenceAssembler.assignRefs(store.listReusable("conv-1", NOW));
    }

    private EvidenceBinder.BindResult bind(CompiledPlan compiled, List<EvidenceAssembler.Entry> candidates) {
        return binder.bind(compiled, candidates, "conv-1", CTX, "t", events::add);
    }

    @Test
    void pinned命中生成REUSE_FACT并落决策事件() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact();
        ObjectNode group = metricGroup("m1", "price.last", "snapshot", "binance");
        group.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");
        CompiledPlan compiled = compile(group);

        assertNull(binder.checkProtocol(compiled.logicalPlan(), candidates));
        EvidenceBinder.BindResult result = bind(compiled, candidates);

        assertNull(result.error(), result.error());
        var reuse = result.bindings().reuseFor("metric_0");
        assertNotNull(reuse);
        assertEquals(candidates.get(0).fact().factId(), reuse.factId());
        assertEquals("turn-1", reuse.sourceTurnId());
        assertEquals(83000, reuse.payload().path("value").asDouble());
        assertEquals(1, result.bindings().reusedCount());
        var decisions = events.stream().filter(e -> e.eventType().equals("EVIDENCE_REUSE_DECISION")).toList();
        assertEquals(1, decisions.size());
        assertTrue(decisions.get(0).inputJson().contains("\"decision\":\"REUSE\""));
        assertTrue(decisions.get(0).inputJson().contains("\"fact_id\":\"" + reuse.factId() + "\""));
    }

    @Test
    void ref不在候选列表时协议校验拒绝() {
        ObjectNode group = metricGroup("m1", "price.last", "snapshot", "binance");
        group.put("reference_mode", "pinned_result").put("evidence_ref", "fact_9");
        CompiledPlan compiled = compile(group);

        String error = binder.checkProtocol(compiled.logicalPlan(), List.of());
        assertNotNull(error);
        assertTrue(error.contains("evidence_ref"), error);
        assertTrue(error.contains("列表为空"), error);
    }

    @Test
    void 身份不符拒绝并落REJECT事件() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact();
        ObjectNode group = metricGroup("m1", "price.last", "snapshot", "okx");
        group.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");

        EvidenceBinder.BindResult result = bind(compile(group), candidates);

        assertNotNull(result.error());
        assertTrue(result.error().contains("交易所不符"), result.error());
        assertTrue(events.stream().anyMatch(e -> e.eventType().equals("EVIDENCE_REUSE_DECISION")
                && e.inputJson().contains("\"decision\":\"REJECT\"")));
    }

    @Test
    void 目录版本不符拒绝() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact();
        EvidenceFact fact = candidates.get(0).fact();
        fact.identity().put("catalog_version", "v0-旧口径");
        ObjectNode group = metricGroup("m1", "price.last", "snapshot", "binance");
        group.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");

        EvidenceBinder.BindResult result = bind(compile(group), candidates);

        assertNotNull(result.error());
        assertTrue(result.error().contains("目录版本不符"), result.error());
    }

    @Test
    void 已清理与已过期都不可恢复() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact();
        repository.rows.clear(); // 模拟已清理（候选来自本轮开始前的快照）
        ObjectNode group = metricGroup("m1", "price.last", "snapshot", "binance");
        group.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");
        var result = bind(compile(group), candidates);
        assertNotNull(result.error());
        assertTrue(result.error().contains("已被清理"), result.error());

        // 已过期：库存还在但 retained_until 已过
        List<EvidenceAssembler.Entry> expired = seedSnapshotFact();
        RequestContext later = new RequestContext(ZoneId.of("Asia/Shanghai"),
                NOW.plus(EvidenceStore.RETENTION).plusSeconds(60));
        var expiredResult = binder.bind(compile(group), expired, "conv-1", later, "t", events::add);
        assertNotNull(expiredResult.error());
        assertTrue(expiredResult.error().contains("已过保留期"), expiredResult.error());
    }

    @Test
    void 派生事实不可作为样本集来源() {
        store.saveDerivedFact("conv-1", "turn-1", "trace-1", "avg",
                JSON.createObjectNode().put("value", 100), List.of(), NOW);
        List<EvidenceAssembler.Entry> candidates =
                EvidenceAssembler.assignRefs(store.listReusable("conv-1", NOW));
        ObjectNode group = metricGroup("m1", "price.last", "snapshot", "binance");
        group.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");

        var result = bind(compile(group), candidates);

        assertNotNull(result.error());
        assertTrue(result.error().contains("派生计算结果"), result.error());
    }

    @Test
    void latest缺省强制FETCH不产生绑定() {
        seedSnapshotFact();
        ObjectNode group = metricGroup("m1", "price.last", "snapshot", "binance");
        var result = bind(compile(group),
                EvidenceAssembler.assignRefs(store.listReusable("conv-1", NOW)));

        assertNull(result.error());
        assertEquals(0, result.bindings().reusedCount());
        assertTrue(events.stream().noneMatch(e -> e.eventType().equals("EVIDENCE_REUSE_DECISION")));
    }

    @Test
    void 比较方向正确时放行() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact();
        CompiledPlan compiled = compilePlan(relativeChangePlan(false));
        var result = bind(compiled, candidates);
        assertNull(result.error(), result.error());
        assertNull(binder.checkComparisonInputs(compiled, result.bindings(), candidates));
    }

    @Test
    void pinned旧快照放到current侧时判方向接反() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact();
        CompiledPlan compiled = compilePlan(relativeChangePlan(true));
        var result = bind(compiled, candidates);
        assertNull(result.error(), result.error());
        String error = binder.checkComparisonInputs(compiled, result.bindings(), candidates);
        assertNotNull(error);
        assertTrue(error.contains("方向接反"), error);
    }

    @Test
    void 比较输入字面量与候选快照值一致时拦截() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact(); // 快照 value=83000
        ObjectNode plan = JSON.createObjectNode();
        plan.putArray("metrics").add(metricGroup("m2", "price.last", "snapshot", "binance"));
        ObjectNode c1 = JSON.createObjectNode().put("id", "c1").put("operator", "relative_change");
        c1.putObject("input").put("current", "83000").put("baseline", "{{m2.binance.value}}");
        plan.putArray("calculations").add(c1);
        CompiledPlan compiled = compilePlan(plan);
        var result = bind(compiled, candidates);
        assertNull(result.error(), result.error());
        String error = binder.checkComparisonInputs(compiled, result.bindings(), candidates);
        assertNotNull(error);
        assertTrue(error.contains("字面量"), error);
        assertTrue(error.contains("fact_1"), error);
    }

    @Test
    void 用户明确给出的常量不匹配候选时不误拦() {
        List<EvidenceAssembler.Entry> candidates = seedSnapshotFact();
        ObjectNode plan = JSON.createObjectNode();
        plan.putArray("metrics").add(metricGroup("m2", "price.last", "snapshot", "binance"));
        ObjectNode c1 = JSON.createObjectNode().put("id", "c1").put("operator", "relative_change");
        c1.putObject("input").put("current", "100000").put("baseline", "{{m2.binance.value}}");
        plan.putArray("calculations").add(c1);
        CompiledPlan compiled = compilePlan(plan);
        var result = bind(compiled, candidates);
        assertNull(result.error(), result.error());
        assertNull(binder.checkComparisonInputs(compiled, result.bindings(), candidates));
    }

    @Test
    void 时间形态按消解后的绝对区间比对() throws Exception {
        // 先以 latest 编译出有效条件，消解出绝对区间造 payload，再入库
        ObjectNode reference = metricGroup("m1", "price.close", "time_series", "binance");
        reference.put("interval", "1h").set("time", JSON.readTree(ABS_RANGE));
        CompiledPlan referencePlan = compile(reference);
        EffectiveTarget target = referencePlan.targets().get(0);
        var spec = com.cexpilot.time.TimeSpecParser.parse(target.timeExpression());
        var range = new com.cexpilot.time.TimeRangeResolver(
                Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"))).resolve(ZoneId.of("Asia/Shanghai"), spec, NOW);
        ObjectNode payload = payloadFor(target, 0);
        payload.set("requested_range", EvidenceBinder.rangeJson(range));
        payload.putArray("samples");
        store.saveFact("conv-1", "turn-1", "trace-1", target, payload, NOW);
        List<EvidenceAssembler.Entry> candidates =
                EvidenceAssembler.assignRefs(store.listReusable("conv-1", NOW));

        // 同一绝对区间 → 命中
        ObjectNode same = metricGroup("m1", "price.close", "time_series", "binance");
        same.put("interval", "1h").set("time", JSON.readTree(ABS_RANGE));
        same.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");
        var hit = bind(compile(same), candidates);
        assertNull(hit.error(), hit.error());
        assertNotNull(hit.bindings().reuseFor("metric_0"));

        // 区间不同（哪怕只是日期偏移）→ 拒绝，不能拿旧窗口冒充
        ObjectNode shifted = metricGroup("m1", "price.close", "time_series", "binance");
        shifted.put("interval", "1h").set("time", JSON.readTree(ABS_RANGE.replace("\"day\":10", "\"day\":9")));
        shifted.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");
        var miss = bind(compile(shifted), candidates);
        assertNotNull(miss.error());
        assertTrue(miss.error().contains("绝对区间不符"), miss.error());
    }
}
