package com.cexpilot.dag;

import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.cexpilot.metric.InstrumentSpec;
import com.cexpilot.metric.LogicalPlan;
import com.cexpilot.metric.MetricRequest;
import com.cexpilot.metric.QueryShape;
import com.cexpilot.metric.TimeQueryParams;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 继承对账（阶段一 c）：计划与活动 frame 的实际 diff 必须 ⊆ Planner 声明的 changes。
 * 对账漏报是"工具成功、答案流畅、查询对象错了"类事故的最后一道程序防线。
 */
class InheritanceValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final Instant T1 = Instant.parse("2026-10-12T01:26:53Z");

    private final InheritanceValidator validator = new InheritanceValidator();

    private static EffectiveTarget target(String exchange, String base, String metric,
                                          String timeJson, String interval) throws Exception {
        JsonNode instrument = MAPPER.readTree(
                "{\"market_type\":\"perpetual\",\"base\":\"" + base + "\",\"quote\":\"USDT\",\"settle\":\"USDT\"}");
        JsonNode time = timeJson == null ? null : MAPPER.readTree(timeJson);
        return new EffectiveTarget("m1", "metric_0", exchange, com.cexpilot.metric.ConditionOrigin.EXPLICIT,
                metric, "range_statistic", instrument, com.cexpilot.metric.ConditionOrigin.DEFAULT,
                time, interval, com.cexpilot.metric.ConditionOrigin.DEFAULT,
                false, com.cexpilot.metric.ConditionOrigin.DEFAULT,
                null, null, null, "USDT", "kline", "v1");
    }

    private static QueryFrame frame(EffectiveTarget... targets) {
        return new QueryFrame("frame-1", "conv-1", 1, "turn-1", "昨天币安 BTC 最高价",
                T1.toString(), SH.getId(), List.of(targets), null, null, null);
    }

    private static CompiledPlan plan(EffectiveTarget target, String timeJson) throws Exception {
        List<MetricRequest> metrics = timeJson == null ? List.of()
                : List.of(new MetricRequest("m1", target.metric(),
                        new InstrumentSpec("perpetual",
                                target.instrument().path("base").asText(), "USDT", "USDT"),
                        QueryShape.RANGE_STATISTIC, List.of(target.exchange()),
                        new TimeQueryParams(TimeSpecParser.parse(MAPPER.readTree(timeJson)), target.interval(), false)));
        return new CompiledPlan(new LogicalPlan(metrics, List.of()), MAPPER.createObjectNode(),
                new DagPlan(List.of()), Map.of(), List.of(target));
    }

    private static final String YESTERDAY =
            "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"full\",\"extent\":\"full_period\"}";

    @Test
    void 无活动frame或frame无targets时放行() throws Exception {
        EffectiveTarget t = target("binance", "BTC", "price.low", YESTERDAY, "5m");
        assertNull(validator.check(null, plan(t, YESTERDAY), Set.of(), new RequestContext(SH, T1)));
        assertNull(validator.check(frame(), plan(t, YESTERDAY), Set.of(), new RequestContext(SH, T1)));
        assertNull(validator.check(frame(t), plan(t, YESTERDAY), Set.of(), null));
    }

    @Test
    void 只换指标且声明metric时放行() throws Exception {
        EffectiveTarget from = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        EffectiveTarget to = target("binance", "BTC", "price.low", YESTERDAY, "5m");
        assertNull(validator.check(frame(from), plan(to, YESTERDAY), Set.of("metric"),
                new RequestContext(SH, T1)));
    }

    @Test
    void 计划偷改交易所未声明时打回并点名exchange() throws Exception {
        EffectiveTarget from = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        EffectiveTarget to = target("okx", "BTC", "price.low", YESTERDAY, "5m");
        String error = validator.check(frame(from), plan(to, YESTERDAY), Set.of("metric"),
                new RequestContext(SH, T1));
        assertNotNull(error);
        assertTrue(error.contains("exchange"), error);
    }

    @Test
    void 多声明不拦截_只拦漏报() throws Exception {
        EffectiveTarget from = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        EffectiveTarget to = target("binance", "BTC", "price.low", YESTERDAY, "5m");
        assertNull(validator.check(frame(from), plan(to, YESTERDAY), Set.of("metric", "time"),
                new RequestContext(SH, T1)));
    }

    @Test
    void 昨天改成前天且未声明time时打回() throws Exception {
        EffectiveTarget from = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        String dayBefore = "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-2,\"segment\":\"full\",\"extent\":\"full_period\"}";
        EffectiveTarget to = target("binance", "BTC", "price.high", dayBefore, "5m");
        String error = validator.check(frame(from), plan(to, dayBefore), Set.of(),
                new RequestContext(SH, T1));
        assertNotNull(error);
        assertTrue(error.contains("time"), error);
    }

    @Test
    void 跨天重述昨天_消解区间不同_未声明time时打回() throws Exception {
        // frame 在 10-12 解析的"昨天"=10-11；本轮请求时间是 10-13，同一表达消解为 10-12 → time 变化
        EffectiveTarget from = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        EffectiveTarget to = target("binance", "BTC", "price.high",
                "{\"type\":\"absolute_range\",\"timezone\":\"Asia/Shanghai\","
                        + "\"start\":{\"year\":2026,\"month\":10,\"day\":11},"
                        + "\"end\":{\"year\":2026,\"month\":10,\"day\":12},\"end_mode\":\"exclusive\"}",
                "5m");
        Instant nextDay = Instant.parse("2026-10-13T01:00:00Z");
        // absolute_range 与 frame 的 calendar_period 指向同一区间 10-11：不算变化
        assertNull(validator.check(frame(from), plan(to,
                "{\"type\":\"absolute_range\",\"timezone\":\"Asia/Shanghai\","
                        + "\"start\":{\"year\":2026,\"month\":10,\"day\":11},"
                        + "\"end\":{\"year\":2026,\"month\":10,\"day\":12},\"end_mode\":\"exclusive\"}"),
                Set.of(), new RequestContext(SH, nextDay)));
    }

    @Test
    void 滚动窗口同时长不算time变化() throws Exception {
        String rolling = "{\"type\":\"rolling_window\",\"duration\":{\"value\":6,\"unit\":\"hour\"}}";
        EffectiveTarget from = target("binance", "BTC", "price.high", rolling, "5m");
        EffectiveTarget to = target("binance", "BTC", "price.low", rolling, "5m");
        // 跨轮请求时间不同，rolling window 消解区间必然不同；同时长应视为条件延续
        assertNull(validator.check(frame(from), plan(to, rolling), Set.of("metric"),
                new RequestContext(SH, T1.plusSeconds(3600))));
    }

    @Test
    void 多对象frame只改其中一个币时只对账出base() throws Exception {
        EffectiveTarget btc = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        EffectiveTarget okxEth = target("okx", "ETH", "price.high", YESTERDAY, "5m");
        EffectiveTarget sol = target("binance", "SOL", "price.high", YESTERDAY, "5m");
        String error = validator.check(frame(btc, okxEth), plan(sol, YESTERDAY), Set.of("metric"),
                new RequestContext(SH, T1));
        assertNotNull(error);
        assertTrue(error.contains("base"), error);
        assertTrue(error.contains("exchange"), error); // okx/ETH 节点消失也算 exchange 变化
    }

    @Test
    void 配对互换_字段集合完全相同_必须检出() throws Exception {
        // R10：币安BTC + OKX ETH → 币安ETH + OKX BTC。旧实现按字段集合比较，
        // exchange/base 集合两侧完全一致会漏检。配对对账（同所优先）下每个节点
        // base 都变 → 至少要求声明 base，changes=[] 的静默换对象通道被堵死
        EffectiveTarget binanceBtc = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        EffectiveTarget okxEth = target("okx", "ETH", "price.high", YESTERDAY, "5m");
        EffectiveTarget binanceEth = target("binance", "ETH", "price.high", YESTERDAY, "5m");
        EffectiveTarget okxBtc = target("okx", "BTC", "price.high", YESTERDAY, "5m");
        String error = validator.check(frame(binanceBtc, okxEth),
                twoTargetPlan(binanceEth, okxBtc, YESTERDAY), Set.of(), new RequestContext(SH, T1));
        assertNotNull(error, "配对互换不得漏检");
        assertTrue(error.contains("base"), error);
    }

    @Test
    void 新增目标未声明时检出() throws Exception {
        // 单目标 frame → 双目标计划（新增 SOL）：新目标的 exchange/base/metric 计入变化
        EffectiveTarget btc = target("binance", "BTC", "price.high", YESTERDAY, "5m");
        EffectiveTarget sol = target("binance", "SOL", "price.high", YESTERDAY, "5m");
        String error = validator.check(frame(btc), twoTargetPlan(btc, sol, YESTERDAY), Set.of(),
                new RequestContext(SH, T1));
        assertNotNull(error);
        assertTrue(error.contains("base"), error);
    }

    @Test
    void 同身份重复组豁免_新旧对比双组不再误伤() throws Exception {
        // "比刚才涨了多少"的正确计划 = pinned(旧快照) + latest(新取数) 两组，查询身份完全相同，
        // 用户未改任何条件。三模型（glm/deepseek-v4.1/deepseek-0731）在此全部被误判漏报
        // changes=[base,exchange,metric]，每次白烧 57~127s 修复轮（trace 实证）。
        // 豁免后：changes=[] 应直接放行。
        EffectiveTarget t = target("binance", "BTC", "price.last", YESTERDAY, "5m");
        Set<String> diff = validator.diffKeys(frame(t), twoTargetPlan(t, t, YESTERDAY),
                new RequestContext(SH, T1));
        assertTrue(diff.isEmpty(), "同身份重复组不得计为条件变化: " + diff);
        assertNull(validator.check(frame(t), twoTargetPlan(t, t, YESTERDAY), Set.of(),
                new RequestContext(SH, T1)));
    }

    @Test
    void 同所同币多指标的不同时间窗口未变时不误报() {
        var original = multiWindowPlan(-2, false);
        var f = new QueryFrame("f", "conv-1", 1, "t1", "两个区间", T1.toString(), SH.getId(),
                original.targets(), null, null, null);
        org.junit.jupiter.api.Assertions.assertEquals(Set.of(), validator.diffKeys(f, original, new RequestContext(SH, T1)));
        org.junit.jupiter.api.Assertions.assertEquals(Set.of(), validator.diffKeys(f, multiWindowPlan(-2, true),
                new RequestContext(SH, T1)), "重排节点不能改变配对");
    }

    @Test
    void 同所同币的第二个指标改变时间必须检出() {
        var original = multiWindowPlan(-2, false);
        var f = new QueryFrame("f", "conv-1", 1, "t1", "两个区间", T1.toString(), SH.getId(),
                original.targets(), null, null, null);
        org.junit.jupiter.api.Assertions.assertEquals(Set.of("time"),
                validator.diffKeys(f, multiWindowPlan(-1, false), new RequestContext(SH, T1)));
        assertNotNull(validator.check(f, multiWindowPlan(-1, true), Set.of(), new RequestContext(SH, T1)));
        assertNull(validator.check(f, multiWindowPlan(-1, true), Set.of("time"), new RequestContext(SH, T1)));
    }

    private static CompiledPlan multiWindowPlan(int lowOffset, boolean reverse) {
        var high = MetricTestSupport.metric("high", "price.high", "range_statistic", "binance");
        var low = MetricTestSupport.metric("low", "price.low", "range_statistic", "binance");
        ((com.fasterxml.jackson.databind.node.ObjectNode) low.get("time")).put("offset", lowOffset);
        var logical = reverse ? MetricTestSupport.plan(low, high) : MetricTestSupport.plan(high, low);
        return new com.cexpilot.metric.MetricPlanCompiler(new com.cexpilot.metric.MetricCatalog(MetricTestSupport.LOADER),
                MetricTestSupport.registry(), MetricTestSupport.catalogProviders()).compileFull(logical, 8);
    }

    /** 双目标计划（每个目标独立 MetricRequest，供配对对账用例构造）。 */
    private static CompiledPlan twoTargetPlan(EffectiveTarget t1, EffectiveTarget t2,
                                              String timeJson) throws Exception {
        MetricRequest r1 = new MetricRequest(t1.groupId(), t1.metric(),
                new InstrumentSpec("perpetual", t1.instrument().path("base").asText(), "USDT", "USDT"),
                QueryShape.RANGE_STATISTIC, List.of(t1.exchange()),
                new TimeQueryParams(TimeSpecParser.parse(MAPPER.readTree(timeJson)), t1.interval(), false));
        MetricRequest r2 = new MetricRequest("m2", t2.metric(),
                new InstrumentSpec("perpetual", t2.instrument().path("base").asText(), "USDT", "USDT"),
                QueryShape.RANGE_STATISTIC, List.of(t2.exchange()),
                new TimeQueryParams(TimeSpecParser.parse(MAPPER.readTree(timeJson)), t2.interval(), false));
        return new CompiledPlan(new LogicalPlan(List.of(r1, r2), List.of()), MAPPER.createObjectNode(),
                new DagPlan(List.of()), Map.of(), List.of(t1, t2));
    }
}
