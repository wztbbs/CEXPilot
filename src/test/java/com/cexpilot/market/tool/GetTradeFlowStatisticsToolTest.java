package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TradePoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.market.trade.TradeQueryService;
import com.cexpilot.market.trade.TradeSource;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.time.TimeRangeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * get_trade_flow_statistics 端到端：完整区间输出买卖流量统计，分页中止时不输出。
 */
class GetTradeFlowStatisticsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;

    /** 2 笔主动买（1.0 @100、2.0 @100）+ 1 笔主动卖（1.0 @100），落在请求区间内。 */
    private static List<TradePoint> trades(long baseMs) {
        return List.of(
                new TradePoint("1", baseMs + 60_000, new BigDecimal("100"), BigDecimal.ONE, true),
                new TradePoint("2", baseMs + 120_000, new BigDecimal("100"), new BigDecimal("2"), true),
                new TradePoint("3", baseMs + 180_000, new BigDecimal("100"), BigDecimal.ONE, false));
    }

    private static TradeSource source(String abortReason) {
        return new TradeSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(), 1000, 20);
            }

            @Override
            public FetchResult fetch(String base, long startMs, long endMs) {
                return new FetchResult(trades(startMs), abortReason);
            }
        };
    }

    private static GetTradeFlowStatisticsTool tool(String abortReason) {
        return new GetTradeFlowStatisticsTool(null, new TradeQueryService(List.of(source(abortReason)),
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
        JsonNode stats = result.data().path("statistics");
        assertEquals(3, stats.path("trade_count").asLong());
        assertTrue(stats.path("trade_count_kind").asText().contains("聚合成交"));
        assertEquals(0, new BigDecimal("3").compareTo(stats.path("buy_volume").decimalValue()));
        assertEquals(0, BigDecimal.ONE.compareTo(stats.path("sell_volume").decimalValue()));
        assertEquals(0, new BigDecimal("300").compareTo(stats.path("buy_quote_volume").decimalValue()));
        assertEquals(0, new BigDecimal("100").compareTo(stats.path("sell_quote_volume").decimalValue()));
        // 买占比 = 3 / 4 = 0.75
        assertEquals(0, new BigDecimal("0.7500").compareTo(stats.path("buy_volume_ratio").decimalValue()));
        assertEquals("2026-09-23 10:01:00",
                stats.path("actual_range").path("first_trade_time").asText());
        assertEquals("2026-09-23 10:03:00",
                stats.path("actual_range").path("last_trade_time").asText());
        assertTrue(result.data().path("complete").asBoolean());
    }

    @Test
    void statisticsOmittedWhenFetchAborted() {
        ToolResult result = tool("分页请求预算用尽（20 页）").execute(args(), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertFalse(facts.path("complete").asBoolean());
        assertTrue(facts.path("statistics").isMissingNode());
        assertTrue(facts.path("statistics_omitted").asText().contains("不输出区间统计"));
        assertEquals("分页请求预算用尽（20 页）", facts.path("abort_reason").asText());
    }

    @Test
    void statisticsOmittedWhenRangeEndBeyondRequestTime() {
        // CR02：请求基准 12:03，「今天全天」终点未到 → complete=false，统计被抑制
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
        assertFalse(facts.path("complete").asBoolean());
        assertTrue(facts.path("statistics").isMissingNode());
        assertTrue(facts.path("statistics_omitted").asText().contains("不输出区间统计"));
        assertTrue(facts.path("abort_reason").asText().contains("请求基准时间"));
    }
}
