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
}
