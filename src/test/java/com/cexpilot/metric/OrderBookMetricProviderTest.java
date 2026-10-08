package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 盘口快照 Provider + 假订单簿；不访问网络。 */
class OrderBookMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final long DATA_TIME = Instant.parse("2026-09-29T11:59:30Z").toEpochMilli();

    private static OrderBook book(String unit, List<OrderBook.Level> bids, List<OrderBook.Level> asks, long time) {
        return new OrderBook(bids, asks, unit, time);
    }

    private static OrderBook.Level level(String price, String qty) {
        return new OrderBook.Level(new BigDecimal(price), new BigDecimal(qty));
    }

    private static OrderBook fullBook(String unit) {
        return book(unit, List.of(level("100.0", "2"), level("99.5", "1")),
                List.of(level("100.5", "3"), level("101.0", "1")), DATA_TIME);
    }

    private static MetricBinding binding(OrderBookSnapshot selector, String unit) {
        ObjectNode instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC")
                .put("quote", "USDT").put("settle", "USDT");
        return new MetricBinding("m1", "orderbook.test", "binance", QueryShape.SNAPSHOT.code(), instrument, unit,
                OrderBookMetricProvider.NAME, selector, "test-v1");
    }

    private static MarketDataService market(OrderBook book) {
        var market = mock(MarketDataService.class);
        when(market.orderBook(any(Exchange.class), anyString(), anyInt())).thenReturn(book);
        return market;
    }

    @Test void snapshotExposesPricesSpreadAndImbalanceOnly() {
        var market = market(fullBook("base"));
        var provider = new OrderBookMetricProvider(market);
        for (var entry : Map.of(OrderBookSnapshot.BEST_BID, "100.0", OrderBookSnapshot.BEST_ASK, "100.5",
                OrderBookSnapshot.SPREAD, "0.5", OrderBookSnapshot.IMBALANCE, "0.7500").entrySet()) {
            var binding = binding(entry.getKey(), entry.getKey() == OrderBookSnapshot.IMBALANCE ? "ratio" : "USDT");
            var point = assertInstanceOf(MetricResult.Point.class,
                    provider.query(new SnapshotQuery(binding, 20), new RequestContext(ZoneOffset.UTC, NOW)),
                    entry.getKey().name());
            assertEquals(0, new BigDecimal(entry.getValue()).compareTo(point.value()), entry.getKey().name());
            assertEquals("2026-09-29 11:59:30", MetricResultJson.write(binding, point).path("as_of").asText());
        }
    }

    @Test void depthIsPassedThroughAndMustBeDeclared() {
        var market = market(fullBook("contracts"));
        var provider = new OrderBookMetricProvider(market);
        provider.query(new SnapshotQuery(binding(OrderBookSnapshot.SPREAD, "USDT"), 5),
                new RequestContext(ZoneOffset.UTC, NOW));
        verify(market).orderBook(Exchange.BINANCE, "BTC", 5);
        // 未声明 depth 的查询不能冒充盘口请求
        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.query(new SnapshotQuery(binding(OrderBookSnapshot.SPREAD, "USDT")),
                        new RequestContext(ZoneOffset.UTC, NOW)));
        assertTrue(error.getMessage().contains("depth"), error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotQuery(binding(OrderBookSnapshot.SPREAD, "USDT"), 100));
    }

    @Test void emptySideIsOmittedNotZero() {
        var market = market(book("base", List.of(), List.of(level("100.5", "3")), DATA_TIME));
        var binding = binding(OrderBookSnapshot.SPREAD, "USDT");
        var omitted = assertInstanceOf(MetricResult.Omitted.class,
                new OrderBookMetricProvider(market).query(new SnapshotQuery(binding, 20),
                        new RequestContext(ZoneOffset.UTC, NOW)));
        var json = MetricResultJson.write(binding, omitted);
        assertFalse(json.has("value"));
        assertTrue(json.path("statistics_omitted").asText().contains("为空"));
        assertThrows(IllegalArgumentException.class,
                () -> new com.cexpilot.calculation.AvgTool().validateSource(json));
    }

    @Test void missingDataTimeIsRejected() {
        var market = market(book("base", List.of(level("100", "1")), List.of(level("101", "1")), 0L));
        var error = assertThrows(IllegalArgumentException.class,
                () -> new OrderBookMetricProvider(market).query(
                        new SnapshotQuery(binding(OrderBookSnapshot.SPREAD, "USDT"), 20),
                        new RequestContext(ZoneOffset.UTC, NOW)));
        assertTrue(error.getMessage().contains("数据时间"), error.getMessage());
    }

    @Test void contractOrderBooksStillYieldComparablePricesAndRatio() {
        // OKX 挂单量是张数；价格与买卖之比不受单位影响，仍然可比
        var market = market(fullBook("contracts"));
        var provider = new OrderBookMetricProvider(market);
        var spread = assertInstanceOf(MetricResult.Point.class, provider.query(
                new SnapshotQuery(binding(OrderBookSnapshot.SPREAD, "USDT"), 20),
                new RequestContext(ZoneOffset.UTC, NOW)));
        assertEquals(0, new BigDecimal("0.5").compareTo(spread.value()));
        var imbalance = assertInstanceOf(MetricResult.Point.class, provider.query(
                new SnapshotQuery(binding(OrderBookSnapshot.IMBALANCE, "ratio"), 20),
                new RequestContext(ZoneOffset.UTC, NOW)));
        assertEquals(0, new BigDecimal("0.7500").compareTo(imbalance.value()));
    }
}
