package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.market.taker.TakerVolumeQueryService;
import com.cexpilot.market.taker.TakerVolumeSource;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.time.TakerInterval;
import com.cexpilot.time.TimeRangeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * get_trade_flow_statistics 端到端：完整覆盖区间输出主动买卖流量统计，
 * 分页中止或区间终点未到时（coverage.range_complete=false）不输出 statistics。
 */
class GetTradeFlowStatisticsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long FIVE_M_MS = 300_000L;

    /** 覆盖 [startMs, endMs) 内相对 NOW 已完结的全部 5m 网格点：每点主动买 2.0、主动卖 1.0。 */
    private static List<TakerVolumePoint> closedGrid(long startMs, long endMs) {
        List<TakerVolumePoint> points = new ArrayList<>();
        for (long t = startMs; t < endMs && t + FIVE_M_MS <= NOW.toEpochMilli(); t += FIVE_M_MS) {
            points.add(new TakerVolumePoint(t, new BigDecimal("2"), BigDecimal.ONE));
        }
        return points;
    }

    private static TakerVolumeSource source(String abortReason) {
        return new TakerVolumeSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(TakerInterval.FIVE_MINUTES), 500, 20);
            }

            @Override
            public FetchResult fetch(String base, long startMs, long endMs) {
                return new FetchResult(closedGrid(startMs, endMs), abortReason);
            }
        };
    }

    private static GetTradeFlowStatisticsTool tool(String abortReason) {
        return new GetTradeFlowStatisticsTool(null, new TakerVolumeQueryService(List.of(source(abortReason)),
                new TimeRangeResolver(Clock.fixed(NOW, UTC))));
    }

    private static JsonNode args() {
        try {
            return MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\","
                    + "\"time\":{\"type\":\"absolute_range\",\"timezone\":\"UTC\","
                    + "\"start\":{\"year\":2026,\"month\":9,\"day\":23,\"time\":\"10:00:00\"},"
                    + "\"end\":{\"year\":2026,\"month\":9,\"day\":23,\"time\":\"11:00:00\"},"
                    + "\"end_mode\":\"exclusive\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolContext ctx() {
        return new ToolContext("t", null, UTC, NOW);
    }

    @Test
    void completeRangeYieldsHandCheckedFlowStats() {
        ToolResult result = tool(null).execute(args(), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertTrue(facts.path("coverage").path("range_complete").asBoolean());
        JsonNode stats = facts.path("statistics");
        // 12 个点：买 2.0×12=24，卖 1.0×12=12，买占比 = 24/36 = 0.6667
        assertEquals(0, new BigDecimal("24").compareTo(stats.path("buy_volume").decimalValue()));
        assertEquals(0, new BigDecimal("12").compareTo(stats.path("sell_volume").decimalValue()));
        assertEquals(0, new BigDecimal("0.6667").compareTo(stats.path("buy_volume_ratio").decimalValue()));
        assertEquals(12, stats.path("point_count").asInt());
        assertEquals("2026-09-23 10:00:00",
                stats.path("actual_range").path("start_inclusive").asText());
        assertEquals("2026-09-23 10:55:00",
                stats.path("actual_range").path("end_inclusive").asText());
        // 成交额与笔数字段已移除
        assertTrue(stats.path("buy_quote_volume").isMissingNode());
        assertTrue(stats.path("trade_count").isMissingNode());
    }

    @Test
    void statisticsOmittedWhenFetchAborted() {
        ToolResult result = tool("分页请求预算用尽（20 页）").execute(args(), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertFalse(facts.path("coverage").path("range_complete").asBoolean());
        assertTrue(facts.path("statistics").isMissingNode());
        assertTrue(facts.path("statistics_omitted").asText().contains("不输出区间统计"));
        assertEquals("分页请求预算用尽（20 页）", facts.path("coverage").path("abort_reason").asText());
    }

    @Test
    void statisticsOmittedWhenRangeEndBeyondRequestTime() {
        // 请求基准 12:03，「今天全天」终点未到 → range_complete=false，统计被抑制
        JsonNode today;
        try {
            today = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\","
                    + "\"time\":{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\","
                    + "\"offset\":0,\"segment\":\"full\",\"extent\":\"full_period\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ToolResult result = tool(null).execute(today, ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertFalse(facts.path("coverage").path("range_complete").asBoolean());
        assertTrue(facts.path("statistics").isMissingNode());
        assertTrue(facts.path("statistics_omitted").asText().contains("不输出区间统计"));
    }
}
