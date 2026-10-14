package com.cexpilot.dag;

import com.cexpilot.metric.*;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;

class MetricPlanCompilerRegressionTest {
    private MetricPlanCompiler compiler() {
        return new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"calendar_period\",\"timezone\":\"America/New_York\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"afternoon\",\"extent\":\"full_period\"}",
        "{\"type\":\"rolling_window\",\"duration\":{\"value\":6,\"unit\":\"hour\"}}",
        "{\"type\":\"relative_day_range\",\"start\":{\"day_offset\":-1,\"time\":\"15:00:00\"},\"end\":{\"day_offset\":-1,\"time\":\"17:00:00\"}}",
        "{\"type\":\"absolute_range\",\"start\":{\"year\":2026,\"month\":9,\"day\":1,\"time\":\"15:00:00\"},\"end\":{\"year\":2026,\"month\":9,\"day\":2},\"end_mode\":\"exclusive\"}"
    })
    void timeTraceUsesProtocolAndRoundTrips(String input) {
        var p = scalarPlan();
        ((ObjectNode)p.at("/metrics/0")).set("time", json(input));
        var node = compiler().compile(p, 8).nodes().get(0);
        assertEquals(json(input), json(node.args().path("time").toString()));
        assertEquals(((TimeRangeQuery)node.metricQuery()).time(), TimeSpecParser.parse(node.args().path("time")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967306", "-4294967286", "9223372036854775818", "10.5", "\"10\"", "null"})
    void rejectsCountAndDepthWithoutCoercion(String input) {
        for (String field : new String[]{"count", "depth"}) {
            var m = metric("m1", field.equals("count") ? "funding.rate_settled" : "orderbook.best_bid",
                    field.equals("count") ? "recent_n" : "snapshot", "binance");
            m.remove("time"); m.remove("interval"); m.set(field, json(input));
            var error = assertThrows(IllegalArgumentException.class, () -> compiler().compile(plan(m), 8));
            assertTrue(error.getMessage().contains("metrics[0]." + field), error.getMessage());
        }
    }

    @Test void collectsIndependentMetricAndCalculationErrorsWithNodeIdentity() {
        var a = metric("m1", "trade.turnover_24h", "official_24h", "okx");
        a.remove("time"); a.remove("interval");
        var b = metric("m2", "price.close", "time_series", "binance"); b.put("interval", "2h");
        var p = plan(a, b);
        calculation(p, "c1", "avg", "{}");
        String error = assertThrows(IllegalArgumentException.class, () -> compiler().compile(p, 8)).getMessage();
        assertTrue(error.contains("metrics[0] (m1, trade.turnover_24h, official_24h)"), error);
        assertTrue(error.contains("exchanges 暂不支持交易所 okx"), error);
        assertTrue(error.contains("metrics[1] (m2, price.close, time_series)"), error);
        assertTrue(error.contains("interval 仅支持"), error);
        assertTrue(error.contains("calculations[0] (c1, avg).input"), error);
        assertTrue(error.contains("输入契约"), error);
    }

    @Test void compiledPlan保留LogicalPlan映射与有效条件来源() {
        var m1 = metric("m1", "price.high", "range_statistic", "binance", "okx");
        m1.remove("interval"); // 不显式给粒度 → DEFAULT（自动粒度）
        var m2 = metric("m2", "price.close", "time_series", "binance"); // interval=1h 显式
        m2.putObject("instrument").put("market_type", "perpetual").put("base", "ETH").put("quote", "USDT")
                .put("settle", "USDT"); // settle 显式
        var p = plan(m1, m2);

        CompiledPlan compiled = compiler().compileFull(p, 8);

        assertEquals(2, compiled.logicalPlan().metrics().size());
        assertEquals(p, compiled.logicalPlanJson());
        assertEquals(3, compiled.dagPlan().nodes().size()); // m1 两交易所 + m2 单交易所
        assertEquals(Map.of("binance", "metric_0", "okx", "metric_1"), compiled.branches().get("m1"));
        assertEquals(Map.of("binance", "metric_2"), compiled.branches().get("m2"));

        assertEquals(3, compiled.targets().size());
        EffectiveTarget binanceHigh = compiled.targets().get(0);
        assertEquals("m1", binanceHigh.groupId());
        assertEquals("metric_0", binanceHigh.physicalId());
        assertEquals("binance", binanceHigh.exchange());
        assertEquals(ConditionOrigin.PLANNED, binanceHigh.exchangeOrigin());
        assertEquals("price.high", binanceHigh.metric());
        assertEquals("range_statistic", binanceHigh.queryShape());
        assertEquals("BTC", binanceHigh.instrument().path("base").asText());
        assertEquals("USDT", binanceHigh.instrument().path("settle").asText());
        assertEquals(ConditionOrigin.DEFAULT, binanceHigh.settleOrigin()); // settle 省略由编译器补 USDT
        assertEquals("calendar_period", binanceHigh.timeExpression().path("type").asText());
        assertNull(binanceHigh.interval());
        assertEquals(ConditionOrigin.DEFAULT, binanceHigh.intervalOrigin()); // 自动粒度
        assertEquals(false, binanceHigh.includeUnclosed());
        assertEquals(ConditionOrigin.DEFAULT, binanceHigh.includeUnclosedOrigin());
        assertEquals("USDT", binanceHigh.unit());
        assertNotNull(binanceHigh.provider());
        assertNotNull(binanceHigh.catalogVersion());

        EffectiveTarget ethClose = compiled.targets().get(2);
        assertEquals("m2", ethClose.groupId());
        assertEquals("ETH", ethClose.instrument().path("base").asText());
        assertEquals(ConditionOrigin.PLANNED, ethClose.settleOrigin());
        assertEquals("1h", ethClose.interval());
        assertEquals(ConditionOrigin.PLANNED, ethClose.intervalOrigin());
    }

    @Test void compiledPlan快照节点记录depth来源() {
        var m = metric("m1", "orderbook.spread", "snapshot", "binance");
        m.remove("time"); m.remove("interval");

        CompiledPlan withDefault = compiler().compileFull(plan(m), 8);
        EffectiveTarget target = withDefault.targets().get(0);
        assertNotNull(target.depth());
        assertEquals(ConditionOrigin.DEFAULT, target.depthOrigin());
        assertNull(target.timeExpression());

        m.put("depth", 10);
        CompiledPlan withExplicit = compiler().compileFull(plan(m), 8);
        assertEquals(10, withExplicit.targets().get(0).depth());
        assertEquals(ConditionOrigin.PLANNED, withExplicit.targets().get(0).depthOrigin());
    }

    @Test void values里塞samples序列引用时静态打回并给出正确形式() {
        // deepseek-v4.1-flash 线上实错：avg 把 {{m1.binance.samples}} 塞进 values，
        // schema 校验放行、执行期才炸（第 0 个值必须是数值），Answer 只能说"无法给出均值"
        var p = scalarPlan();
        calculation(p, "c1", "avg", "{\"kind\":\"values\",\"values\":[\"{{m1.binance.samples}}\"]}");

        var error = assertThrows(IllegalArgumentException.class, () -> compiler().compile(p, 8)).getMessage();
        assertTrue(error.contains("序列聚合必须用"), error);
        assertTrue(error.contains("\"kind\":\"field\""), error);
        assertTrue(error.contains("{{m1.binance.samples}}"), error);
    }

    @Test void field的collection指向标量引用时静态打回() {
        // 镜像错配：对标量做聚合
        var p = scalarPlan();
        calculation(p, "c1", "avg", "{\"kind\":\"field\",\"collection\":\"{{m1.binance.value}}\",\"field\":\"value\"}");

        var error = assertThrows(IllegalArgumentException.class, () -> compiler().compile(p, 8)).getMessage();
        assertTrue(error.contains("collection 必须是序列引用"), error);
    }

    @Test void values里放标量引用和元素引用仍然合法() throws Exception {
        // 对照：标量引用 {{m1.binance.value}} 与单元素引用 {{m1.binance.samples.0.value}} 不受影响
        var p = scalarPlan();
        calculation(p, "c1", "avg",
                "{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\",\"{{m1.binance.samples.0.value}}\",100]}");
        assertNotNull(compiler().compile(p, 8));
    }
}
