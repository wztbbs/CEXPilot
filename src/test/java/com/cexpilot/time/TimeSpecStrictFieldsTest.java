package com.cexpilot.time;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cexpilot.metric.LogicalPlanParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TimeSpecStrictFieldsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"rolling_window\",\"duration\":{\"value\":6,\"unit\":\"hour\",\"end_time\":\"2026-10-01\"}}",
        "{\"type\":\"relative_day_range\",\"start\":{\"day_offset\":-1,\"time\":\"15:00:00\",\"year\":2026},\"end\":{\"day_offset\":-1,\"time\":\"17:00:00\"}}",
        "{\"type\":\"absolute_range\",\"start\":{\"month\":9,\"day\":1},\"end\":{\"month\":9,\"day\":2,\"day_offset\":-1},\"end_mode\":\"exclusive\"}"
    })
    void rejectsNestedUnknownFieldsAtBothEntrypoints(String time) throws Exception {
        var node = JSON.readTree(time);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(node))
                .getMessage().contains("含未知字段"));
        var plan = JSON.createObjectNode(); plan.putArray("calculations");
        var metric = plan.putArray("metrics").addObject();
        metric.put("id", "m1").put("metric", "price.close").put("query_shape", "range_statistic");
        metric.putArray("exchanges").add("binance");
        metric.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        metric.set("time", node);
        String error = assertThrows(IllegalArgumentException.class, () -> new LogicalPlanParser().parse(plan)).getMessage();
        assertTrue(error.contains("metrics[0].time"), error);
        assertTrue(error.contains("含未知字段"), error);
    }
}
