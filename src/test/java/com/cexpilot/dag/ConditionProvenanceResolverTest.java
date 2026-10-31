package com.cexpilot.dag;

import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.metric.*;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionProvenanceResolverTest {
    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final RequestContext CONTEXT = new RequestContext(ZONE, NOW);
    private final MetricPlanCompiler compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
    private final ConditionProvenanceResolver resolver = new ConditionProvenanceResolver();

    private CompiledPlan resolve(JsonNode plan, String claims, String question, QueryFrame base) {
        return resolver.resolve(compiler, plan, claims == null ? null : json(claims), question, base, CONTEXT, 12);
    }

    private QueryFrame saved(CompiledPlan plan, String turn, long version, String interval) {
        var evidence = JSON.createArrayNode();
        if (interval != null) {
            var entry = evidence.addObject().put("node_id", "metric_0").put("ok", true);
            var data = entry.putObject("data").put("candle_interval", interval).put("interval_source", "automatic");
            data.set("requested_range", json("{\"start_inclusive\":\"2026-10-12 00:00:00\",\"end_exclusive\":\"2026-10-13 00:00:00\",\"timezone\":\"Asia/Shanghai\"}"));
            data.putObject("actual_range").put("start_inclusive", "2026-10-12 00:00:00");
            data.putObject("coverage").put("range_complete", true);
            data.putArray("samples").addObject().put("value", 123);
        }
        return QueryFrame.fromJson(QueryFrame.fromCompiledPlan("frame-old", version, "conv", turn, "原问题",
                NOW, ZONE, plan, evidence, "SUCCEEDED").toJson());
    }

    @Test void 编译字段不能仅因模型填写就记为用户明确要求() {
        var target = resolve(scalarPlan(), null, "昨天成交额", null).targets().get(0);
        assertEquals(ConditionOrigin.PLANNED, target.intervalOrigin());
        assertEquals(ConditionOrigin.PLANNED, target.exchangeOrigin());
        assertEquals(ConditionOrigin.DEFAULT, target.includeUnclosedOrigin());
        assertEquals(ConditionOrigin.PLANNED, target.conditionSources().get("time").origin());
    }

    @Test void 用户原文依据及来源标识随任务版本持久化() {
        var plan = resolve(scalarPlan(), "{\"m1\":{\"interval\":\"1小时\",\"exchange\":\"币安\"}}", "查币安，用1小时粒度", null);
        var target = saved(plan, "turn-1", 3, null).targets().get(0);
        assertEquals(ConditionOrigin.EXPLICIT, target.intervalOrigin());
        var source = target.conditionSources().get("interval");
        assertEquals("1小时", source.userText());
        assertEquals("turn-1", source.sourceTurnId());
        assertEquals("frame-old", source.sourceFrameId());
        assertEquals(3L, source.sourceVersion());
    }

    @Test void 自动粒度与执行范围独立保存且模型抄回5m后仍自动选择() {
        var p = scalarPlan(); ((ObjectNode)p.at("/metrics/0")).remove("interval");
        var base = saved(resolve(p, null, "昨天成交额", null), "turn-1", 2, "5m");
        var old = base.targets().get(0);
        assertNull(old.interval());
        assertEquals("5m", old.executionMetadata().path("candle_interval").asText());
        assertEquals(old.executionMetadata().get("requested_range"), old.executionMetadata().get("effective_range"));
        assertTrue(old.executionMetadata().has("actual_range"));
        assertTrue(old.executionMetadata().has("coverage"));
        assertFalse(old.executionMetadata().has("samples"));

        var next = scalarPlan(); ((ObjectNode)next.at("/metrics/0")).put("interval", "5m");
        var targetPlan = resolve(next, null, "那最低价呢", base);
        var target = targetPlan.targets().get(0);
        assertNull(target.interval());
        assertFalse(targetPlan.dagPlan().nodes().get(0).args().has("interval"));
        assertFalse(targetPlan.logicalPlanJson().at("/metrics/0").has("interval"));
        assertNull(target.executionMetadata()); // 新执行还没发生，不能复制旧实际范围。
        assertEquals(ConditionOrigin.INHERITED, target.intervalOrigin());
        var source = target.conditionSources().get("interval");
        assertEquals(ConditionOrigin.DEFAULT, source.requirementOrigin());
        assertEquals("turn-1", source.sourceTurnId());
        assertEquals(2L, source.sourceVersion());
    }

    @Test void 用户本轮明确指定5m时不能把它恢复成自动() {
        var p = scalarPlan(); ((ObjectNode)p.at("/metrics/0")).remove("interval");
        var base = saved(resolve(p, null, "成交额", null), "turn-1", 1, "5m");
        var next = scalarPlan(); ((ObjectNode)next.at("/metrics/0")).put("interval", "5m");
        var target = resolve(next, "{\"m1\":{\"interval\":\"5分钟\"}}", "固定用5分钟粒度", base).targets().get(0);
        assertEquals("5m", target.interval());
        assertEquals(ConditionOrigin.EXPLICIT, target.intervalOrigin());
        assertEquals(ConditionOrigin.EXPLICIT, target.conditionSources().get("interval").requirementOrigin());
    }

    @Test void 用户疑似明确要求固定粒度但模型漏报原文时保留未核实计划而不忽略用户() {
        var p = scalarPlan(); ((ObjectNode)p.at("/metrics/0")).remove("interval");
        var base = saved(resolve(p, null, "成交额", null), "turn-1", 1, "5m");
        var next = scalarPlan(); ((ObjectNode)next.at("/metrics/0")).put("interval", "5m");
        var target = resolve(next, null, "固定用5分钟粒度", base).targets().get(0);
        assertEquals("5m", target.interval());
        assertEquals(ConditionOrigin.PLANNED, target.intervalOrigin());
        assertEquals(ConditionOrigin.PLANNED, target.conditionSources().get("interval").requirementOrigin());
    }

    @Test void 换窗口仍沿用用户固定粒度即使模型漏填() {
        var base = saved(resolve(scalarPlan(), "{\"m1\":{\"interval\":\"1小时\"}}", "用1小时粒度", null), "turn-1", 4, null);
        var next = scalarPlan();
        ((ObjectNode)next.at("/metrics/0")).remove("interval");
        ((ObjectNode)next.at("/metrics/0/time")).put("offset", -2);
        var result = resolve(next, null, "换成前天", base);
        var source = result.targets().get(0).conditionSources().get("interval");
        assertEquals("1h", result.targets().get(0).interval());
        assertEquals("1h", result.dagPlan().nodes().get(0).args().path("interval").asText());
        assertEquals(ConditionOrigin.INHERITED, source.origin());
        assertEquals(ConditionOrigin.EXPLICIT, source.requirementOrigin());
        assertEquals(4L, source.sourceVersion());
        assertEquals(ConditionOrigin.PLANNED, result.targets().get(0).conditionSources().get("time").origin());
        var secondFrame = saved(result, "turn-2", 5, null);
        var third = resolve(result.logicalPlanJson(), null, "再查一次", secondFrame);
        var thirdSource = third.targets().get(0).conditionSources().get("interval");
        assertEquals("turn-2", thirdSource.sourceTurnId());
        assertEquals(5L, thirdSource.sourceVersion());
        assertEquals("turn-1", thirdSource.requirementTurnId());
        assertEquals(4L, thirdSource.requirementVersion());
    }

    @Test void 用户明确改回自动解除旧固定粒度且后续可以继续自动() {
        var base = saved(resolve(scalarPlan(), "{\"m1\":{\"interval\":\"1小时\"}}", "1小时", null), "turn-1", 1, null);
        var next = scalarPlan(); ((ObjectNode)next.at("/metrics/0")).remove("interval");
        var automatic = resolve(next, "{\"m1\":{\"interval\":\"自动\"}}", "改回自动", base);
        assertNull(automatic.targets().get(0).interval());
        assertEquals(ConditionOrigin.EXPLICIT, automatic.targets().get(0).intervalOrigin());
        var autoFrame = saved(automatic, "turn-2", 2, "5m");
        var copied = scalarPlan(); ((ObjectNode)copied.at("/metrics/0")).put("interval", "5m");
        assertNull(resolve(copied, null, "换昨天", autoFrame).targets().get(0).interval());
    }

    @Test void 旧来源原文无效或字段未知时降级并记录warning不拒绝有效计划() {
        var warnings = new java.util.ArrayList<ConditionProvenanceResolver.Warning>();
        var result = resolver.resolve(compiler, scalarPlan(), json("""
                {"m1":{"interval":"1小时","source_turn_id":"那"},"made_up":{"interval":"那"}}
                """), null, "那最低价呢", null, CONTEXT, 12, warnings::add);
        assertEquals(3, warnings.size());
        assertEquals(ConditionOrigin.PLANNED, result.targets().get(0).intervalOrigin());
        assertNull(result.targets().get(0).conditionSources().get("interval").userText());
    }

    private CompiledPlan update(JsonNode plan, String actions, QueryFrame base) {
        return resolver.resolve(compiler, plan, null, json(actions), "本轮参数操作", base, CONTEXT, 12, ignored -> {});
    }

    @Test void 新协议先固定粒度后恢复自动并且后续不抄回执行粒度() {
        var fixed = update(scalarPlan(), "{\"m1\":{\"interval\":\"set\"}}", null);
        assertEquals(ConditionOrigin.EXPLICIT, fixed.targets().get(0).intervalOrigin());
        assertNull(fixed.targets().get(0).conditionSources().get("interval").userText());
        var base = saved(fixed, "turn-fixed", 1, null);
        var next = scalarPlan(); ((ObjectNode)next.at("/metrics/0")).remove("interval");
        var automatic = update(next, "{\"m1\":{\"interval\":\"reset\"}}", base);
        assertNull(automatic.targets().get(0).interval());
        assertFalse(automatic.logicalPlanJson().at("/metrics/0").has("interval"));
        assertEquals(ConditionOrigin.DEFAULT, automatic.targets().get(0).conditionSources().get("interval").requirementOrigin());
        var autoFrame = saved(automatic, "turn-auto", 2, "5m");
        var copied = scalarPlan(); ((ObjectNode)copied.at("/metrics/0")).put("interval", "5m");
        assertNull(resolve(copied, null, "换成前天", autoFrame).targets().get(0).interval());
    }

    @Test void 新协议没有操作时沿用固定参数而set同值仍算本轮要求() {
        var automatic = scalarPlan(); ((ObjectNode)automatic.at("/metrics/0")).remove("interval");
        var base = saved(resolve(automatic, null, "昨天价格", null), "turn-auto", 1, "5m");
        var next = scalarPlan(); ((ObjectNode)next.at("/metrics/0")).put("interval", "5m");
        var fixed = update(next, "{\"m1\":{\"interval\":\"set\"}}", base);
        assertEquals("5m", fixed.targets().get(0).interval());
        var fixedFrame = saved(fixed, "turn-fixed", 2, null);
        ((ObjectNode)next.at("/metrics/0")).remove("interval");
        assertEquals("5m", update(next, "{}", fixedFrame).targets().get(0).interval());
    }

    @Test void 多个可选参数可同时恢复默认且不被旧值覆盖() {
        var p = scalarPlan();
        ((ObjectNode)p.at("/metrics/0")).put("include_unclosed", true);
        ((ObjectNode)p.at("/metrics/0/instrument")).put("settle", "USDT");
        var base = saved(update(p, "{\"m1\":{\"interval\":\"set\",\"include_unclosed\":\"set\",\"settle\":\"set\"}}", null), "fixed", 1, null);
        var next = scalarPlan();
        ((ObjectNode)next.at("/metrics/0")).remove("interval");
        var reset = update(next, "{\"m1\":{\"interval\":\"reset\",\"include_unclosed\":\"reset\",\"settle\":\"reset\"}}", base);
        var target = reset.targets().get(0);
        assertNull(target.interval()); assertFalse(target.includeUnclosed());
        assertEquals("USDT", target.instrument().path("settle").asText());
        for (String field : List.of("interval", "include_unclosed", "settle"))
            assertEquals(ConditionOrigin.DEFAULT, target.conditionSources().get(field).requirementOrigin());
        var frame = saved(reset, "reset", 2, "5m");
        var continued = update(next, "{}", frame).targets().get(0);
        assertNull(continued.interval()); assertFalse(continued.includeUnclosed());
        assertEquals(ConditionOrigin.DEFAULT, continued.conditionSources().get("include_unclosed").requirementOrigin());
    }

    @Test void 盘口深度明确设置成默认数值仍固定而reset恢复目录默认() {
        var metric = metric("m1", "orderbook.spread", "snapshot", "binance");
        metric.remove(List.of("interval", "time"));
        var initial = plan(metric);
        var base = saved(resolve(initial, null, "查盘口", null), "default", 1, null);
        metric.put("depth", 20);
        var explicit = update(plan(metric), "{\"m1\":{\"depth\":\"set\"}}", base);
        assertEquals(ConditionOrigin.EXPLICIT, explicit.targets().get(0).depthOrigin());
        metric.put("depth", 50);
        var fixed = saved(update(plan(metric), "{\"m1\":{\"depth\":\"set\"}}", base), "fixed", 2, null);
        metric.remove("depth");
        assertEquals(50, update(plan(metric), "{}", fixed).targets().get(0).depth());
        var reset = update(plan(metric), "{\"m1\":{\"depth\":\"reset\"}}", fixed);
        assertEquals(20, reset.targets().get(0).depth());
        assertFalse(reset.logicalPlanJson().at("/metrics/0").has("depth"));
        assertEquals(ConditionOrigin.DEFAULT, reset.targets().get(0).conditionSources().get("depth").requirementOrigin());
    }

    @Test void 旧原文无效且忽略会改变继承时一次反馈全部相关字段() {
        var p = scalarPlan(); ((ObjectNode)p.at("/metrics/0")).put("include_unclosed", true);
        var base = saved(update(p, "{\"m1\":{\"interval\":\"set\",\"include_unclosed\":\"set\"}}", null), "fixed", 1, null);
        var next = scalarPlan(); ((ObjectNode)next.at("/metrics/0")).remove("interval");
        var error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(compiler, next,
                json("{\"m1\":{\"interval\":\"假原文1\",\"include_unclosed\":\"假原文2\"}}"), null,
                "恢复默认", base, CONTEXT, 12, ignored -> {}));
        assertTrue(error.getMessage().contains("explicit_conditions.m1.interval"));
        assertTrue(error.getMessage().contains("explicit_conditions.m1.include_unclosed"));
        assertTrue(error.getMessage().contains("condition_updates"));
        assertDoesNotThrow(() -> resolver.resolve(compiler, next,
                json("{\"m1\":{\"interval\":\"假原文1\",\"include_unclosed\":\"假原文2\"}}"),
                json("{\"m1\":{\"interval\":\"reset\",\"include_unclosed\":\"reset\"}}"),
                "恢复默认", base, CONTEXT, 12, ignored -> {}));
    }

    @Test void 操作矛盾及未知字段组一次反馈而不修改原计划() {
        var p = scalarPlan(); var original = p.deepCopy();
        var error = assertThrows(IllegalArgumentException.class, () -> update(p, """
                {"m1":{"interval":"reset","depth":"set","include_unclosed":"bad","base":"set"},"made_up":{"interval":"set"}}
                """, null));
        for (String field : List.of("m1.interval", "m1.depth", "m1.include_unclosed", "m1.base", "made_up"))
            assertTrue(error.getMessage().contains(field), error.getMessage());
        assertEquals(original, p);
    }

    @Test void 不适用的操作和真正的计划错误都不能因来源降级而放行() {
        var metric = metric("m1", "price.last", "snapshot", "binance"); metric.remove(List.of("interval", "time"));
        var invalidActions = assertThrows(IllegalArgumentException.class, () -> update(plan(metric), """
                {"m1":{"interval":"reset","depth":"reset","include_unclosed":"reset"}}
                """, null));
        for (String field : List.of("interval", "depth", "include_unclosed"))
            assertTrue(invalidActions.getMessage().contains("m1." + field));
        metric.putObject("time");
        assertThrows(IllegalArgumentException.class, () -> resolve(plan(metric), "{\"m1\":{\"time\":\"现在\"}}", "现在", null));
    }

    @Test void 同一指标组两所固定粒度不同时要求拆组而不是拿第一条() {
        var a = metric("a", "price.high", "range_statistic", "binance");
        var b = metric("b", "price.high", "range_statistic", "okx"); b.put("interval", "5m");
        var base = saved(resolve(plan(a,b), null, "查询", null), "turn-1", 1, null);
        var both = metric("both", "price.high", "range_statistic", "binance", "okx"); both.remove("interval");
        var error = assertThrows(IllegalArgumentException.class, () -> resolve(plan(both), null, "两所再查", base));
        assertTrue(error.getMessage().contains("拆成独立指标组"));
    }

    @Test void 两个时间窗口换序仍保留正确来源配对() {
        var a = metric("a", "price.high", "range_statistic", "binance");
        var b = metric("b", "price.high", "range_statistic", "binance");
        ((ObjectNode)b.path("time")).put("offset", -2);
        var compiled = resolve(plan(a,b), "{\"a\":{\"time\":\"昨天\"},\"b\":{\"time\":\"前天\"}}", "昨天和前天", null);
        var base = saved(compiled, "turn-old", 7, null);
        var reversed = resolve(plan(b,a), null, "再查一次", base);
        assertEquals("前天", reversed.targets().get(0).conditionSources().get("time").userText());
        assertEquals("昨天", reversed.targets().get(1).conditionSources().get("time").userText());
        assertEquals(7L, reversed.targets().get(0).conditionSources().get("time").sourceVersion());
    }

    @Test void 旧快照可读取但旧EXPLICIT不能追认用户原文() {
        var p = resolve(scalarPlan(), null, "查数据", null);
        var legacyTarget = p.targets().get(0).withMetadata(java.util.Map.of(), null);
        var legacy = new QueryFrame("legacy", "conv", 1, "old-turn", "旧问题", NOW.toString(), ZONE.getId(),
                List.of(legacyTarget), null, null, null);
        var result = resolve(scalarPlan(), null, "再查", QueryFrame.fromJson(legacy.toJson()));
        var source = result.targets().get(0).conditionSources().get("interval");
        assertEquals(ConditionOrigin.INHERITED, source.origin());
        assertEquals(ConditionOrigin.PLANNED, source.requirementOrigin());
    }

    @Test void 回访以所选旧版本的条件来源为准而不是当前任务() {
        var originalPlan = scalarPlan(); ((ObjectNode)originalPlan.at("/metrics/0")).remove("interval");
        var original = saved(resolve(originalPlan, null, "昨天成交额", null), "original-turn", 2, "5m");
        var current = saved(resolve(scalarPlan(), "{\"m1\":{\"interval\":\"1小时\"}}", "1小时", null), "current-turn", 9, null);
        current = new QueryFrame("frame-current", "conv", 9, current.sourceTurnId(), "当前任务", NOW.toString(), ZONE.getId(),
                current.targets(), null, null, null);
        var originalState = com.cexpilot.conversation.TaskState.legacy(original, 2, "最初查询");
        var candidate = com.cexpilot.conversation.TaskSnapshot.candidate(originalState, "task_original");
        var copied = scalarPlan(); ((ObjectNode)copied.at("/metrics/0")).put("interval", "5m");
        var env = (ObjectNode)json(envelope(copied));
        env.put("task_action", "RESUME").put("task_ref", candidate.taskRef()).put("state_ref", candidate.stateRef());
        var config = new com.cexpilot.config.DagConfig(); config.setPlannerMaxRetries(0);
        var outcome = planner(new Script(env.toString()), registry(), config).plan("回到最初查询", "", "test", e -> {},
                current, CONTEXT, null, List.of(candidate), com.cexpilot.conversation.TaskState.legacy(current, 9, "当前任务"));
        var target = outcome.plan().orElseThrow().targets().get(0);
        assertNull(target.interval());
        var source = target.conditionSources().get("interval");
        assertEquals("original-turn", source.sourceTurnId());
        assertEquals("frame-old", source.sourceFrameId());
        assertEquals(2L, source.sourceVersion());
        assertEquals(ConditionOrigin.DEFAULT, source.requirementOrigin());
    }

    @Test void 执行失败时只记录已成功节点的元数据() {
        var a = metric("a", "price.high", "range_statistic", "binance"); a.remove("interval");
        var b = metric("b", "price.low", "range_statistic", "okx"); b.remove("interval");
        var plan = resolve(plan(a,b), null, "两所价格", null);
        var evidence = JSON.createArrayNode();
        evidence.addObject().put("node_id", "metric_0").put("ok", true).putObject("data").put("candle_interval", "5m");
        evidence.addObject().put("node_id", "metric_1").put("ok", false).putObject("data").put("candle_interval", "伪值");
        var frame = QueryFrame.fromJson(QueryFrame.fromCompiledPlan("failed-frame", 3, "conv", "failed-turn", "两所价格",
                NOW, ZONE, plan, evidence, "FAILED").toJson());
        assertEquals("5m", frame.targets().get(0).executionMetadata().path("candle_interval").asText());
        assertNull(frame.targets().get(1).executionMetadata());
    }
}
