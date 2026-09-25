package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.funding.FundingQueryService;
import com.cexpilot.market.funding.FundingRateSource;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.runtime.ToolResult;
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
 * get_funding_rate_history 端到端（fake source + 固定时钟）：time 模式覆盖核对、count 模式样本语义。
 */
class GetFundingRateHistoryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long EIGHT_H_MS = 8 * 3_600_000L;
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    private static FundingRateSource source() {
        return new FundingRateSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return new SeriesCapability(Set.of(), 1000, 10);
            }

            @Override
            public long fundingIntervalMs(String base) {
                return EIGHT_H_MS;
            }

            @Override
            public FetchResult fetch(String base, long intervalMs, long startMs, long endMs) {
                List<FundingRatePoint> points = new ArrayList<>();
                int i = 0;
                for (long t = startMs % intervalMs == 0 ? startMs : (startMs / intervalMs + 1) * intervalMs;
                     t < endMs && t <= NOW.toEpochMilli(); t += intervalMs, i++) {
                    points.add(new FundingRatePoint(new BigDecimal("0.0001"), t));
                }
                return new FetchResult(points, null);
            }
        };
    }

    private static GetFundingRateHistoryTool tool() {
        return new GetFundingRateHistoryTool(null, new FundingQueryService(List.of(source()),
                new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC))));
    }

    private static com.cexpilot.runtime.ToolContext ctx() {
        return new com.cexpilot.runtime.ToolContext("t", null, null, NOW);
    }

    @Test
    void timeModeReturnsCoverageAndRates() {
        try {
            JsonNode args = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\","
                    + "\"time\":{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                    + "\"segment\":\"full\",\"extent\":\"full_period\"}}");
            ToolResult result = tool().execute(args, ctx());
            assertTrue(result.ok(), () -> String.valueOf(result.error()));
            JsonNode facts = result.data();
            assertEquals("time_range", facts.path("mode").asText());
            assertEquals(8, facts.path("funding_interval_hours").asInt());
            assertTrue(facts.path("coverage").path("range_complete").asBoolean());
            assertEquals(3, facts.path("period_count").asInt());
            assertEquals(3, facts.path("rates").size());
            assertEquals("2026-09-23 00:00:00", facts.path("rates").get(0).get(0).asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void countModeReturnsRecentSamples() {
        try {
            JsonNode args = MAPPER.readTree(
                    "{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"count\":5}");
            ToolResult result = tool().execute(args, ctx());
            assertTrue(result.ok(), () -> String.valueOf(result.error()));
            JsonNode facts = result.data();
            assertEquals("recent_count", facts.path("mode").asText());
            assertEquals(5, facts.path("requested_count").asInt());
            assertEquals(5, facts.path("actual_count").asInt());
            assertEquals(5, facts.path("rates").size());
            // 最近 5 期最新一期 09-24T08:00Z，按请求时区（默认 UTC+8）渲染
            assertEquals("2026-09-24 16:00:00", facts.path("rates").get(4).get(0).asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void timeAndCountAreMutuallyExclusive() {
        try {
            JsonNode both = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"count\":5,"
                    + "\"time\":{\"type\":\"rolling_window\",\"duration\":{\"value\":24,\"unit\":\"hour\"}}}");
            assertFalse(tool().execute(both, ctx()).ok());
            JsonNode neither = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\"}");
            assertFalse(tool().execute(neither, ctx()).ok());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
