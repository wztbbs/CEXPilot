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
 * get_funding_rate_statistics 端到端：完整区间输出统计，缺期不输出。
 */
class GetFundingRateStatisticsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long EIGHT_H_MS = 8 * 3_600_000L;
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");

    /** 昨天 3 期费率：+0.0001 / -0.0002 / +0.0003（skip=true 时缺第二期）。 */
    private static FundingRateSource source(boolean skipSecond) {
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
                long day = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();
                BigDecimal[] rates = {new BigDecimal("0.0001"), new BigDecimal("-0.0002"), new BigDecimal("0.0003")};
                List<FundingRatePoint> points = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    if (skipSecond && i == 1) {
                        continue;
                    }
                    long t = day + i * EIGHT_H_MS;
                    if (t >= startMs && t < endMs) {
                        points.add(new FundingRatePoint(rates[i], t));
                    }
                }
                return new FetchResult(points, null);
            }
        };
    }

    private static GetFundingRateStatisticsTool tool(FundingRateSource source) {
        return new GetFundingRateStatisticsTool(null, new FundingQueryService(List.of(source),
                new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC))));
    }

    private static JsonNode args() {
        try {
            return MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\","
                    + "\"time\":{\"type\":\"calendar_period\",\"timezone\":\"UTC\",\"unit\":\"day\",\"offset\":-1,"
                    + "\"segment\":\"full\",\"extent\":\"full_period\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.cexpilot.runtime.ToolContext ctx() {
        return new com.cexpilot.runtime.ToolContext("t", null, null, NOW);
    }

    @Test
    void completeRangeYieldsStatistics() {
        ToolResult result = tool(source(false)).execute(args(), ctx());
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode stats = result.data().path("statistics");
        // mean = (0.0001 - 0.0002 + 0.0003) / 3 = 0.0000666... 保留 8 位小数
        assertEquals("0.00006667", stats.path("mean").asText());
        assertEquals("-0.0002", stats.path("min").asText());
        assertEquals("0.0003", stats.path("max").asText());
        assertEquals(2, stats.path("positive_count").asInt());
        assertEquals(1, stats.path("negative_count").asInt());
        assertEquals(0, stats.path("zero_count").asInt());
        assertEquals(3, stats.path("period_count").asInt());
        assertEquals("2026-09-23 00:00:00", stats.path("actual_range").path("start_inclusive").asText());
        assertEquals("2026-09-23 16:00:00", stats.path("actual_range").path("end_inclusive").asText());
    }

    @Test
    void statisticsOmittedWhenPeriodMissing() {
        // 缺一期 → 间隔不一致，查询响亮失败（不再用单一网格误判缺失后给部分结果）
        ToolResult result = tool(source(true)).execute(args(), ctx());
        assertFalse(result.ok());
        assertTrue(result.error().contains("结算间隔不一致"), () -> result.error());
    }
}
