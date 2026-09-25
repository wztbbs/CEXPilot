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
 * get_trade_history 端到端（fake source + 固定时钟）：口径标注、明细行、中止标注。
 */
class GetTradeHistoryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long START = Instant.parse("2026-09-23T10:00:00Z").toEpochMilli();

    private static List<TradePoint> trades() {
        return List.of(
                new TradePoint("1", START + 60_000, new BigDecimal("100"), new BigDecimal("1.5"), true),
                new TradePoint("2", START + 120_000, new BigDecimal("101"), new BigDecimal("2.5"), false),
                new TradePoint("3", START + 180_000, new BigDecimal("102"), BigDecimal.ONE, true));
    }

    private static TradeSource source(Exchange exchange, String abortReason) {
        return new TradeSource() {
            @Override
            public Exchange exchange() {
                return exchange;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(), 1000, 20);
            }

            @Override
            public FetchResult fetch(String base, long startMs, long endMs) {
                return new FetchResult(trades(), abortReason);
            }
        };
    }

    private static GetTradeHistoryTool tool(TradeSource source) {
        return new GetTradeHistoryTool(null, new TradeQueryService(List.of(source),
                new TimeRangeResolver(Clock.fixed(NOW, UTC))));
    }

    private static JsonNode args(String exchange) {
        try {
            return MAPPER.readTree("{\"exchange\":\"" + exchange + "\",\"symbol\":\"BTC\","
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
    void completeResultLabelsAggTradeKindAndRows() {
        ToolResult result = tool(source(Exchange.BINANCE, null)).execute(args("binance"), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertTrue(facts.path("trade_source_kind").asText().contains("aggTrades"));
        assertEquals(3, facts.path("actual_count").asInt());
        assertTrue(facts.path("complete").asBoolean());
        assertEquals("2026-09-23 10:01:00", facts.path("first_trade_time").asText());
        assertEquals("2026-09-23 10:03:00", facts.path("last_trade_time").asText());
        JsonNode rows = facts.path("trades");
        assertEquals(3, rows.size());
        assertEquals("2026-09-23 10:01:00", rows.get(0).get(0).asText());
        assertEquals("100", rows.get(0).get(1).asText());
        assertEquals("1.5", rows.get(0).get(2).asText());
        assertEquals("buy", rows.get(0).get(3).asText());
        assertEquals("sell", rows.get(1).get(3).asText());
    }

    @Test
    void okxResultLabelsTickByTickKind() {
        ToolResult result = tool(source(Exchange.OKX, null)).execute(args("okx"), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        assertEquals("逐笔成交", result.data().path("trade_source_kind").asText());
    }

    @Test
    void abortedResultKeepsAbortReasonAndPartialNote() {
        ToolResult result = tool(source(Exchange.BINANCE, "分页请求预算用尽"))
                .execute(args("binance"), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertFalse(facts.path("complete").asBoolean());
        assertEquals("分页请求预算用尽", facts.path("abort_reason").asText());
        assertTrue(facts.path("partial_result_note").asText().contains("部分数据"));
    }
}
