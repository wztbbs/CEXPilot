package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.series.BoundaryMode;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.market.kline.KlineQueryRequest;
import com.cexpilot.market.kline.KlineSource;
import com.cexpilot.market.kline.KlineQueryService;
import com.cexpilot.market.model.Candle;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tool 端到端（fake KlineSource + 固定 Clock）：覆盖 TimeSpec 解析、
 * cover 外扩、覆盖核对结果与 facts 结构。
 */
class GetKlinesToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T15:30:00Z");

    /** 按 effective 区间生成完整连续 K 线的假数据源。 */
    private static KlineSource completeSource() {
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
                long price = 100;
                for (long t = effective.range().startInclusive().toEpochMilli();
                     t < effective.range().endExclusive().toEpochMilli(); t += intervalMs) {
                    candles.add(new Candle(t, BigDecimal.valueOf(price), BigDecimal.valueOf(price + 1),
                            BigDecimal.valueOf(price - 1), BigDecimal.valueOf(price + 1), BigDecimal.TEN));
                    price++;
                }
                return new FetchResult(candles, null);
            }
        };
    }

    private static com.cexpilot.runtime.ToolContext ctx(java.time.ZoneId zone) {
        return new com.cexpilot.runtime.ToolContext("t", null, zone, NOW);
    }

    private static GetKlinesTool tool() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        KlineQueryService service = new KlineQueryService(
                List.of(completeSource()), new TimeRangeResolver(clock));
        return new GetKlinesTool(null, service);
    }

    private static JsonNode args(String boundaryMode) {
        try {
            return MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"5m\","
                    + "\"boundary_mode\":\"" + boundaryMode + "\","
                    + "\"time\":{\"type\":\"rolling_window\",\"timezone\":null,"
                    + "\"duration\":{\"value\":61,\"unit\":\"minute\"}}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void coverModeWidensAndReportsCoverage() {
        // [14:29, 15:30]Z 61 分钟 → cover 外扩为 [14:25, 15:30)Z，13 根；facts 按 UTC+8 渲染
        ToolResult result = tool().execute(args("cover"), ctx(null));
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        JsonNode facts = result.data();
        assertEquals("5m", facts.path("candle_interval").asText());
        assertEquals("2026-09-24 22:29:00", facts.path("requested_range").path("start_inclusive").asText());
        assertEquals("2026-09-24 22:25:00", facts.path("effective_range").path("start_inclusive").asText());
        JsonNode coverage = facts.path("coverage");
        assertEquals(13, coverage.path("expected_count").asInt());
        assertEquals(13, coverage.path("actual_count").asInt());
        assertTrue(coverage.path("closed_part_complete").asBoolean());
        assertTrue(coverage.path("range_complete").asBoolean());
        assertFalse(coverage.path("contains_unclosed").asBoolean());
        assertEquals(13, facts.path("candle_count").asInt());
        assertEquals(13, facts.path("candles").size());
        // 区间统计已划归 get_market_statistics，本 tool 不再输出 price_change
        assertTrue(facts.path("price_change").isMissingNode());
    }

    @Test
    void incompleteCoverageIsReportedWithoutStatistics() {
        // 缺一根 K 线：closed_part_complete=false 且 missing 给出具体开盘时间，不伪装完整
        KlineSource gappy = new KlineSource() {
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
                List<Candle> candles = new ArrayList<>(completeSource().fetch(effective).candles());
                candles.remove(0);
                return new FetchResult(candles, null);
            }
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        GetKlinesTool gappyTool = new GetKlinesTool(null,
                new KlineQueryService(List.of(gappy), new TimeRangeResolver(clock)));
        ToolResult result = gappyTool.execute(args("cover"), ctx(null));
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        assertFalse(result.data().path("coverage").path("closed_part_complete").asBoolean());
        assertEquals(1, result.data().path("coverage").path("missing_count").asInt());
        assertTrue(result.data().path("price_change").isMissingNode());
    }

    @Test
    void exactModeRejectsUnalignedRange() {
        ToolResult result = tool().execute(args("exact"), ctx(null));
        assertFalse(result.ok());
        assertTrue(result.error().contains("未对齐"), () -> result.error());
    }

    @Test
    void requestTimezoneFromContextOverridesDefault() {
        // 请求携带 America/New_York 时区：当天 10:00~11:00 按纽约当地日期消解，渲染也按纽约时区
        try {
            JsonNode nyArgs = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\",\"interval\":\"5m\","
                    + "\"time\":{\"type\":\"relative_day_range\",\"timezone\":null,"
                    + "\"start\":{\"day_offset\":0,\"time\":\"10:00:00\"},"
                    + "\"end\":{\"day_offset\":0,\"time\":\"11:00:00\"}}}");
            ToolResult result = tool().execute(nyArgs, ctx(java.time.ZoneId.of("America/New_York")));
            assertTrue(result.ok(), () -> String.valueOf(result.error()));
            JsonNode range = result.data().path("requested_range");
            assertEquals("America/New_York", range.path("timezone").asText());
            assertEquals("2026-09-24 10:00:00", range.path("start_inclusive").asText());
            assertEquals("2026-09-24 11:00:00", range.path("end_exclusive").asText());
            assertEquals(12, result.data().path("coverage").path("expected_count").asInt());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void missingTimeParamFails() {
        try {
            JsonNode noTime = MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\"}");
            ToolResult result = tool().execute(noTime, ctx(null));
            assertFalse(result.ok());
            assertTrue(result.error().contains("time"), () -> result.error());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
