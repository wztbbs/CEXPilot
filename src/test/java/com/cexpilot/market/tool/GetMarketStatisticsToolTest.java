package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.kline.KlineQueryRequest;
import com.cexpilot.market.kline.KlineQueryService;
import com.cexpilot.market.kline.KlineSource;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRangeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * get_market_statistics 端到端（fake KlineSource + 固定时钟）：
 * 区间统计字段、覆盖门控（range_complete=false 时抑制统计）。
 */
class GetMarketStatisticsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T15:30:00Z");

    /** 第 i 根：open=100+i, close=101+i, high=102+i, low=99+i, volume=10, quoteVolume=1000。 */
    private static KlineSource source(boolean dropFirst) {
        return new KlineSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4);
            }

            @Override
            public FetchResult fetch(KlineQueryRequest effective) {
                long intervalMs = effective.interval().duration().toMillis();
                List<Candle> candles = new ArrayList<>();
                int i = 0;
                for (long t = effective.range().startInclusive().toEpochMilli();
                     t < effective.range().endExclusive().toEpochMilli(); t += intervalMs, i++) {
                    if (dropFirst && i == 0) {
                        continue;
                    }
                    candles.add(new Candle(t,
                            BigDecimal.valueOf(100 + i), BigDecimal.valueOf(102 + i),
                            BigDecimal.valueOf(99 + i), BigDecimal.valueOf(101 + i),
                            BigDecimal.TEN, BigDecimal.valueOf(1000), null));
                }
                return new FetchResult(candles, null);
            }
        };
    }

    private static GetMarketStatisticsTool tool(KlineSource source) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new GetMarketStatisticsTool(null,
                new KlineQueryService(List.of(source), new TimeRangeResolver(clock)));
    }

    private static com.cexpilot.runtime.ToolContext ctx() {
        return new com.cexpilot.runtime.ToolContext("t", null, null, NOW);
    }

    private static JsonNode args(String timeJson) {
        try {
            return MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"5m\","
                    + "\"time\":" + timeJson + "}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void completeRangeYieldsFullStatistics() {
        // 昨天 10:00~11:00（UTC+8）= 02:00~03:00Z，12 根，全部已收盘
        ToolResult result = tool(source(false)).execute(args(
                "{\"type\":\"relative_day_range\",\"timezone\":\"UTC\","
                        + "\"start\":{\"day_offset\":-1,\"time\":\"02:00:00\"},"
                        + "\"end\":{\"day_offset\":-1,\"time\":\"03:00:00\"}}"), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertTrue(facts.path("coverage").path("range_complete").asBoolean());
        JsonNode stats = facts.path("statistics");
        assertEquals("100", stats.path("open").asText());
        assertEquals("112", stats.path("close").asText());
        assertEquals("113", stats.path("high").asText());
        assertEquals("99", stats.path("low").asText());
        assertEquals("12.0000", stats.path("change_pct").asText());
        assertEquals("120", stats.path("volume").asText());
        assertEquals("12000", stats.path("quote_volume").asText());
        assertEquals(12, stats.path("candle_count").asInt());
        assertEquals("2026-09-23 02:00:00", stats.path("actual_range").path("start_inclusive").asText());
        assertEquals("2026-09-23 03:00:00", stats.path("actual_range").path("end_exclusive").asText());
        // 不返回 K 线明细
        assertTrue(facts.path("candles").isMissingNode());
    }

    @Test
    void statisticsOmittedWhenCoverageIncomplete() {
        ToolResult result = tool(source(true)).execute(args(
                "{\"type\":\"relative_day_range\",\"timezone\":\"UTC\","
                        + "\"start\":{\"day_offset\":-1,\"time\":\"02:00:00\"},"
                        + "\"end\":{\"day_offset\":-1,\"time\":\"03:00:00\"}}"), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        assertFalse(result.data().path("coverage").path("closed_part_complete").asBoolean());
        assertEquals(1, result.data().path("coverage").path("missing_count").asInt());
        assertTrue(result.data().path("statistics").isMissingNode());
    }

    @Test
    void statisticsOmittedWhenRangeEndBeyondRequestTime() {
        // “今天全天”（UTC）：NOW=15:30Z，区间终点未到 → range_complete=false，统计被抑制；
        // 需要“今天截至现在”时应使用 extent=to_request_time
        ToolResult result = tool(source(false)).execute(args(
                "{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":0,"
                        + "\"segment\":\"full\",\"extent\":\"full_period\"}"), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertTrue(facts.path("coverage").path("closed_part_complete").asBoolean());
        assertFalse(facts.path("coverage").path("range_complete").asBoolean());
        assertEquals("2026-09-24 15:30:00", facts.path("coverage").path("covered_until").asText());
        assertTrue(facts.path("statistics").isMissingNode());
        assertTrue(facts.path("statistics_omitted").asText().contains("range_complete"));
    }
}
