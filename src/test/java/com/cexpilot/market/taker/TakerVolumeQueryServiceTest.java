package com.cexpilot.market.taker;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.TakerInterval;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TakerVolumeQueryService 流程：5m 网格对齐、覆盖核对、中止原因进入 coverage、
 * 缺口判不完整、空区间失败、保留期检查。
 */
class TakerVolumeQueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T12:03:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long FIVE_M_MS = 300_000L;
    private static final long START = Instant.parse("2026-09-23T10:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-09-23T11:00:00Z").toEpochMilli();

    private static TimeSpec spec(String startDay, String endDay) throws Exception {
        return TimeSpecParser.parse(MAPPER.readTree(
                "{\"type\":\"absolute_range\",\"timezone\":\"UTC\","
                        + "\"start\":{\"year\":2026,\"month\":9,\"day\":" + startDay + ",\"time\":\"10:00:00\"},"
                        + "\"end\":{\"year\":2026,\"month\":9,\"day\":" + endDay + ",\"time\":\"11:00:00\"},"
                        + "\"end_mode\":\"exclusive\"}"));
    }

    /** 覆盖 [START, END) 的完整 5m 序列（12 个点），可跳过 skipIndex 制造缺口。 */
    private static List<TakerVolumePoint> fullGrid(int skipIndex) {
        List<TakerVolumePoint> points = new ArrayList<>();
        int i = 0;
        for (long t = START; t < END; t += FIVE_M_MS, i++) {
            if (i == skipIndex) {
                continue;
            }
            points.add(new TakerVolumePoint(t, BigDecimal.ONE, BigDecimal.ONE));
        }
        return points;
    }

    private static TakerVolumeSource fakeSource(List<TakerVolumePoint> points, String abortReason,
                                                Integer retentionDays) {
        return new TakerVolumeSource() {
            @Override
            public Exchange exchange() {
                return Exchange.BINANCE;
            }

            @Override
            public SeriesCapability capability() {
                return retentionDays == null
                        ? new SeriesCapability(Set.of(TakerInterval.FIVE_MINUTES), 500, 20)
                        : new SeriesCapability(Set.of(TakerInterval.FIVE_MINUTES), 500, 20, retentionDays);
            }

            @Override
            public FetchResult fetch(String base, long startMs, long endMs, Instant requestTime) {
                return new FetchResult(points, abortReason);
            }
        };
    }

    private static TakerVolumeQueryService service(TakerVolumeSource source) {
        return new TakerVolumeQueryService(List.of(source), new TimeRangeResolver(Clock.fixed(NOW, UTC)));
    }

    @Test
    void completeGridPassesCoverage() throws Exception {
        TakerVolumeQueryResult result = service(fakeSource(fullGrid(-1), null, null))
                .query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertTrue(result.coverage().rangeComplete());
        assertEquals(12, result.points().size());
        assertEquals(START, result.points().get(0).timestamp());
        assertEquals(END - FIVE_M_MS, result.points().get(11).timestamp());
        // 请求区间本就对齐 5m 网格：effective 与 requested 相同
        assertEquals(result.requested(), result.effective());
    }

    @Test
    void paddingIsRemovedBeforeValidationAndDoesNotChangeCalculationRange() throws Exception {
        var points = fullGrid(-1);
        points.add(0, new TakerVolumePoint(START - FIVE_M_MS, BigDecimal.TEN, BigDecimal.TEN));
        points.add(new TakerVolumePoint(END, BigDecimal.TEN, BigDecimal.TEN));
        points.add(new TakerVolumePoint(END + FIVE_M_MS, BigDecimal.TEN, BigDecimal.TEN));
        var result = service(fakeSource(points, null, null)).query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertTrue(result.coverage().rangeComplete());
        assertEquals(12, result.coverage().expectedCount());
        assertEquals(12, result.points().size());
        assertEquals(fullGrid(-1), result.points());
        assertEquals(START, result.effective().startInclusive().toEpochMilli());
        assertEquals(END, result.effective().endExclusive().toEpochMilli());
        assertTrue(result.coverage().unexpected().isEmpty());
    }

    @Test
    void paddingCannotReplaceMissingLastPeriod() throws Exception {
        var points = fullGrid(11);
        points.add(0, new TakerVolumePoint(START - FIVE_M_MS, BigDecimal.ONE, BigDecimal.ONE));
        points.add(new TakerVolumePoint(END, BigDecimal.ONE, BigDecimal.ONE));
        var result = service(fakeSource(points, null, null)).query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertFalse(result.coverage().rangeComplete());
        assertEquals(11, result.points().size());
        assertEquals(List.of(END - FIVE_M_MS), result.coverage().missing());
    }

    @Test
    void inRangeMisalignmentAndDuplicatesStillFailStrictValidation() throws Exception {
        var points = fullGrid(-1);
        points.add(new TakerVolumePoint(START + 1, BigDecimal.ONE, BigDecimal.ONE));
        points.add(points.get(0));
        var result = service(fakeSource(points, null, null)).query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertFalse(result.coverage().rangeComplete());
        assertEquals(List.of(START + 1), result.coverage().unexpected());
        assertEquals(List.of(START), result.coverage().duplicates());
        assertTrue(result.coverage().missing().isEmpty());
    }

    @Test
    void unalignedRangeWidenedToGrid() throws Exception {
        // 10:02 ~ 10:58 未对齐：外扩到 10:00 ~ 11:00
        TimeSpec spec = TimeSpecParser.parse(MAPPER.readTree(
                "{\"type\":\"absolute_range\",\"timezone\":\"UTC\","
                        + "\"start\":{\"year\":2026,\"month\":9,\"day\":23,\"time\":\"10:02:00\"},"
                        + "\"end\":{\"year\":2026,\"month\":9,\"day\":23,\"time\":\"10:58:00\"},"
                        + "\"end_mode\":\"exclusive\"}"));
        TakerVolumeQueryResult result = service(fakeSource(fullGrid(-1), null, null))
                .query(UTC, spec, NOW, Exchange.BINANCE, "BTC");
        assertTrue(result.coverage().rangeComplete());
        assertEquals(12, result.points().size());
        assertEquals(START, result.effective().startInclusive().toEpochMilli());
        assertEquals(END, result.effective().endExclusive().toEpochMilli());
        assertTrue(result.requested().startInclusive().toEpochMilli() > START);
    }

    @Test
    void abortReasonMarksCoverageIncomplete() throws Exception {
        TakerVolumeQueryResult result = service(fakeSource(fullGrid(-1), "分页请求预算用尽（20 页）", null))
                .query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertFalse(result.coverage().complete());
        assertFalse(result.coverage().rangeComplete());
        assertEquals("分页请求预算用尽（20 页）", result.coverage().abortReason());
        assertEquals(12, result.points().size());
    }

    @Test
    void missingPointMarksCoverageIncomplete() throws Exception {
        TakerVolumeQueryResult result = service(fakeSource(fullGrid(5), null, null))
                .query(UTC, spec("23", "23"), NOW, Exchange.BINANCE, "BTC");
        assertFalse(result.coverage().complete());
        assertEquals(1, result.coverage().missing().size());
        assertEquals(START + 5 * FIVE_M_MS, result.coverage().missing().get(0));
    }

    @Test
    void emptyRangeFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                service(fakeSource(List.of(), null, null))
                        .query(UTC, uncheckedSpec(), NOW, Exchange.BINANCE, "BTC"));
        assertTrue(e.getMessage().contains("没有 taker 成交量数据"), e.getMessage());
    }

    @Test
    void retentionExceededRejected() {
        // 保留 30 天，查询 40 天前的区间 → 查询前拒绝
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                service(fakeSource(List.of(), null, 30))
                        .query(UTC, uncheckedSpec40DaysAgo(), NOW, Exchange.BINANCE, "BTC"));
        assertTrue(e.getMessage().contains("30"), e.getMessage());
    }

    @Test
    void currentUnclosedPeriodDropped() throws Exception {
        // 滚动窗口终点=请求时刻 12:03：12:00 的 5m 周期未完结，从预期中剔除
        TimeSpec rolling = TimeSpecParser.parse(MAPPER.readTree(
                "{\"type\":\"rolling_window\",\"timezone\":\"UTC\","
                        + "\"duration\":{\"value\":1,\"unit\":\"hour\"}}"));
        long end = NOW.toEpochMilli(); // 12:03
        long start = end - 3_600_000L; // 11:03，未对齐 5m 网格
        List<TakerVolumePoint> points = new ArrayList<>();
        // source 返回的是 5m 网格点：从对齐起点 11:00 到最后一个已完结周期 11:55
        for (long t = start / FIVE_M_MS * FIVE_M_MS; t + FIVE_M_MS <= end; t += FIVE_M_MS) {
            points.add(new TakerVolumePoint(t, BigDecimal.ONE, BigDecimal.ONE));
        }
        TakerVolumeQueryResult result = service(fakeSource(points, null, null))
                .query(UTC, rolling, NOW, Exchange.BINANCE, "BTC");
        assertTrue(result.coverage().rangeComplete());
        assertTrue(result.coverage().droppedUnclosed());
        assertEquals(12, result.points().size());
        assertNull(result.coverage().abortReason());
    }

    private static TimeSpec uncheckedSpec() {
        try {
            return spec("23", "23");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TimeSpec uncheckedSpec40DaysAgo() {
        try {
            return TimeSpecParser.parse(MAPPER.readTree(
                    "{\"type\":\"absolute_range\",\"timezone\":\"UTC\","
                            + "\"start\":{\"year\":2026,\"month\":8,\"day\":15,\"time\":\"10:00:00\"},"
                            + "\"end\":{\"year\":2026,\"month\":8,\"day\":15,\"time\":\"11:00:00\"},"
                            + "\"end_mode\":\"exclusive\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
