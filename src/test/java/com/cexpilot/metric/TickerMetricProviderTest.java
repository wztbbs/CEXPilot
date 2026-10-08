package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 快照 Provider + 假 ticker；不访问网络。 */
class TickerMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final long DATA_TIME = Instant.parse("2026-09-29T11:59:30Z").toEpochMilli();

    private static MetricBinding binding(TickerMetric selector, String unit) {
        ObjectNode instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        return new MetricBinding("m1", "test." + selector.name().toLowerCase(), "binance", selector.shape().code(),
                instrument, unit, TickerMetricProvider.NAME, selector, "test-v1");
    }

    private static MarketDataService market(long timestamp, BigDecimal lastPrice) {
        var market = mock(MarketDataService.class);
        when(market.ticker(any(Exchange.class), anyString())).thenReturn(
                new Ticker(lastPrice, new BigDecimal("2.35"), new BigDecimal("12345.678"),
                        new BigDecimal("805432100.25"), false, timestamp));
        return market;
    }

    @Test void snapshotUsesExchangeDataTimeNotRequestTime() {
        var binding = binding(TickerMetric.LAST, "USDT");
        var provider = new TickerMetricProvider(market(DATA_TIME, new BigDecimal("65231.5")));
        var point = assertInstanceOf(MetricResult.Point.class,
                provider.query(new SnapshotQuery(binding), new RequestContext(ZoneOffset.UTC, NOW)));
        assertEquals(0, new BigDecimal("65231.5").compareTo(point.value()));
        var json = MetricResultJson.write(binding, point);
        // 数据时间来自交易所快照戳，不能回落到请求时间
        assertEquals("2026-09-29 11:59:30", json.path("as_of").asText());
        assertEquals("USDT", json.path("unit").asText());
        assertEquals("ticker", json.at("/source/provider").asText());
        assertFalse(json.has("requested_range"));
        assertFalse(json.has("coverage"));
    }

    @Test void snapshotRendersInRequestZone() {
        var binding = binding(TickerMetric.LAST, "USDT");
        var provider = new TickerMetricProvider(market(DATA_TIME, new BigDecimal("65231.5")));
        var point = (MetricResult.Point) provider.query(new SnapshotQuery(binding),
                new RequestContext(ZoneId.of("Asia/Shanghai"), NOW));
        var json = MetricResultJson.write(binding, point);
        assertEquals("2026-09-29 19:59:30", json.path("as_of").asText());
    }

    @Test void missingDataTimeOrValueIsRejectedInsteadOfGuessing() {
        var provider = new TickerMetricProvider(market(0L, new BigDecimal("65231.5")));
        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.query(new SnapshotQuery(binding(TickerMetric.LAST, "USDT")),
                        new RequestContext(ZoneOffset.UTC, NOW)));
        assertTrue(error.getMessage().contains("数据时间"), error.getMessage());
        var noPrice = new TickerMetricProvider(market(DATA_TIME, null));
        var omitted = assertInstanceOf(MetricResult.Omitted.class,
                noPrice.query(new SnapshotQuery(binding(TickerMetric.LAST, "USDT")),
                        new RequestContext(ZoneOffset.UTC, NOW)));
        assertTrue(omitted.reason().contains("未提供"), omitted.reason());
    }

    @Test void official24hReadsExchangeWindowValues() {
        var market = market(DATA_TIME, new BigDecimal("65231.5"));
        var provider = new TickerMetricProvider(market);
        for (var entry : java.util.Map.of(TickerMetric.CHANGE_PCT_24H, "2.35",
                TickerMetric.VOLUME_24H, "12345.678", TickerMetric.TURNOVER_24H, "805432100.25").entrySet()) {
            var binding = binding(entry.getKey(), "percent".equals(entry.getValue()) ? "percent" : "USDT");
            var point = assertInstanceOf(MetricResult.Point.class,
                    provider.query(new SnapshotQuery(binding), new RequestContext(ZoneOffset.UTC, NOW)));
            assertEquals(0, new BigDecimal(entry.getValue()).compareTo(point.value()), entry.getKey().name());
            assertEquals("2026-09-29 11:59:30", MetricResultJson.write(binding, point).path("as_of").asText());
        }
        assertFalse(TickerMetric.CHANGE_PCT_24H.supports(QueryShape.SNAPSHOT.code()));
        assertFalse(TickerMetric.LAST.supports(QueryShape.OFFICIAL_24H.code()));
    }

    @Test void missing24hValueIsOmittedInsteadOfZero() {
        var market = mock(MarketDataService.class);
        when(market.ticker(any(Exchange.class), anyString())).thenReturn(
                new Ticker(new BigDecimal("65231.5"), null, null, null, false, DATA_TIME));
        var binding = binding(TickerMetric.CHANGE_PCT_24H, "percent");
        var omitted = assertInstanceOf(MetricResult.Omitted.class,
                new TickerMetricProvider(market).query(new SnapshotQuery(binding), new RequestContext(ZoneOffset.UTC, NOW)));
        var json = MetricResultJson.write(binding, omitted);
        assertFalse(json.has("value"));
        assertTrue(json.path("statistics_omitted").asText().contains("未提供"));
        assertThrows(IllegalArgumentException.class,
                () -> new com.cexpilot.calculation.AvgTool().validateSource(json));
    }

    @Test void estimatedTurnoverIsOmittedNotReportedAsExact() {
        var market = mock(MarketDataService.class);
        when(market.ticker(any(Exchange.class), anyString())).thenReturn(
                new Ticker(new BigDecimal("98.5"), new BigDecimal("1.2"), new BigDecimal("9"),
                        new BigDecimal("900"), true, DATA_TIME));
        var binding = binding(TickerMetric.TURNOVER_24H, "USDT");
        var omitted = assertInstanceOf(MetricResult.Omitted.class,
                new TickerMetricProvider(market).query(new SnapshotQuery(binding), new RequestContext(ZoneOffset.UTC, NOW)));
        assertTrue(omitted.reason().contains("估算"), omitted.reason());
        // 同一快照的成交量不受影响，仍可正常取值
        var volume = assertInstanceOf(MetricResult.Point.class, new TickerMetricProvider(market)
                .query(new SnapshotQuery(binding(TickerMetric.VOLUME_24H, "BTC")), new RequestContext(ZoneOffset.UTC, NOW)));
        assertEquals(0, new BigDecimal("9").compareTo(volume.value()));
    }

    @Test void snapshotSelectorRefusesRangeShapes() {
        assertFalse(TickerMetric.LAST.supports("range_statistic"));
        assertFalse(TickerMetric.LAST.supports("time_series"));
        assertTrue(TickerMetric.LAST.supports("snapshot"));
        var provider = new TickerMetricProvider(market(DATA_TIME, new BigDecimal("65231.5")));
        // 区间形态的绑定在构造期就被拒，不会走到取数
        assertThrows(IllegalArgumentException.class, () -> new MetricBinding("m1", "price.last", "binance",
                "range_statistic", JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                        .put("quote", "USDT").put("settle", "USDT"),
                "USDT", TickerMetricProvider.NAME, TickerMetric.LAST, "test-v1"));
        assertThrows(IllegalArgumentException.class, () -> provider.query(new TimeRangeQuery(
                binding(TickerMetric.LAST, "USDT"),
                com.cexpilot.time.TimeSpecParser.parse(JSON.readTree(
                        "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"full\",\"extent\":\"full_period\"}")),
                null, false), new RequestContext(ZoneOffset.UTC, NOW)));
    }
}
