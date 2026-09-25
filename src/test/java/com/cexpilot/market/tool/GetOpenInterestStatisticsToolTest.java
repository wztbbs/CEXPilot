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
 * get_open_interest_statistics 端到端：完整区间输出统计，缺采样点不输出。
 */
class GetOpenInterestStatisticsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    /** 第 i 点 oi=1000+i×10（skip=true 时缺第 2 点）。 */
    private static OiSource source(boolean skip) {
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
                    if (skip && i == 2) {
                        continue;
                    }
                    points.add(new OiPoint(t, BigDecimal.valueOf(1000 + i * 10L)));
                }
                return new FetchResult(points, null);
            }
        };
    }

    private static GetOpenInterestStatisticsTool tool(OiSource source) {
        return new GetOpenInterestStatisticsTool(null, new OiQueryService(List.of(source),
                new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC))));
    }

    private static JsonNode args() {
        try {
            return MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"1h\","
                    + "\"time\":{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                    + "\"segment\":\"full\",\"extent\":\"full_period\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void completeRangeYieldsStatistics() {
        ToolResult result = tool(source(false)).execute(args(),
                new com.cexpilot.runtime.ToolContext("t", null, null, NOW));
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode stats = result.data().path("statistics");
        assertEquals("1000", stats.path("start_oi").asText());
        assertEquals("1230", stats.path("end_oi").asText());
        assertEquals("230", stats.path("change").asText());
        assertEquals("23.0000", stats.path("change_pct").asText());
        assertEquals("1230", stats.path("max_oi").asText());
        assertEquals("2026-09-23 23:00:00", stats.path("max_time").asText());
        assertEquals("1000", stats.path("min_oi").asText());
        assertEquals("2026-09-23 00:00:00", stats.path("min_time").asText());
        assertEquals(24, stats.path("point_count").asInt());
        assertEquals("2026-09-23 00:00:00", stats.path("actual_range").path("start_inclusive").asText());
        assertEquals("2026-09-23 23:00:00", stats.path("actual_range").path("end_inclusive").asText());
    }

    @Test
    void statisticsOmittedWhenPointMissing() {
        ToolResult result = tool(source(true)).execute(args(),
                new com.cexpilot.runtime.ToolContext("t", null, null, NOW));
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        assertFalse(result.data().path("coverage").path("closed_part_complete").asBoolean());
        assertTrue(result.data().path("statistics").isMissingNode());
    }
}
