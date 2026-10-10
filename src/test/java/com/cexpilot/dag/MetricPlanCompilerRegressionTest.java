package com.cexpilot.dag;

import com.cexpilot.metric.*;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

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
}
