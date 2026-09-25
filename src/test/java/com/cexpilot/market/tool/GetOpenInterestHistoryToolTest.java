package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.market.oi.OiSource;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.time.OiInterval;
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
 * get_open_interest_history 端到端（fake source + 固定时钟）。
 */
class GetOpenInterestHistoryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    private static OiSource source() {
        return new OiSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(OiInterval.values()), 500, 8);
            }

            @Override
            public FetchResult fetch(String base, OiInterval interval, long startMs, long endMs) {
                List<OiPoint> points = new ArrayList<>();
                int i = 0;
                for (long t = startMs; t < endMs; t += interval.duration().toMillis(), i++) {
                    points.add(new OiPoint(t, BigDecimal.valueOf(1000 + i)));
                }
                return new FetchResult(points, null);
            }
        };
    }

    private static GetOpenInterestHistoryTool tool() {
        return new GetOpenInterestHistoryTool(null, new OiQueryService(List.of(source()),
                new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC))));
    }

    @Test
    void yesterdayReturnsFullSeriesWithCoverage() {
        try {
            JsonNode args = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"1h\","
                    + "\"time\":{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                    + "\"segment\":\"full\",\"extent\":\"full_period\"}}");
            ToolResult result = tool().execute(args,
                    new com.cexpilot.runtime.ToolContext("t", null, null, NOW));
            assertTrue(result.ok(), () -> String.valueOf(result.error()));
            JsonNode facts = result.data();
            assertEquals("1h", facts.path("oi_interval").asText());
            assertEquals("BTC", facts.path("unit").asText());
            assertTrue(facts.path("coverage").path("range_complete").asBoolean());
            assertEquals(24, facts.path("point_count").asInt());
            assertEquals(24, facts.path("oi_series").size());
            assertEquals("2026-09-23 00:00:00", facts.path("oi_series").get(0).get(0).asText());
            assertEquals("1000", facts.path("oi_series").get(0).get(1).asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unsupportedIntervalFails() {
        try {
            JsonNode args = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"3h\","
                    + "\"time\":{\"type\":\"rolling_window\",\"duration\":{\"value\":6,\"unit\":\"hour\"}}}");
            ToolResult result = tool().execute(args,
                    new com.cexpilot.runtime.ToolContext("t", null, null, NOW));
            assertFalse(result.ok());
            assertTrue(result.error().contains("采样粒度"), () -> result.error());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
