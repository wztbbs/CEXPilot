package com.cexpilot.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.*;

class MetricCatalogTest {
    @Test void publicCatalogExposesBindingLimitsWithoutProviderDetails() {
        var catalog = new MetricCatalog(new DefaultResourceLoader());
        String rendered = catalog.describeMetrics();
        String funding = metricLine(rendered, "funding.rate_settled");
        assertTrue(funding.contains("recent_n（count={\"min\":1,\"max\":100}）"));
        assertTrue(funding.contains("未接入当前未结算/预测费率、下次结算时间和时间窗口查询"));
        assertTrue(metricLine(rendered, "orderbook.best_bid")
                .contains("depth={\"default\":20,\"min\":5,\"max\":50}"));
        assertTrue(metricLine(rendered, "price.open")
                .contains("range_statistic|time_series（intervals=[\"5m\",\"15m\",\"1h\"]）"));
        assertTrue(metricLine(rendered, "trade.turnover_24h")
                .contains("official_24h（exchanges=[\"binance\"]）"));
        for (String name : catalog.names()) {
            if (name.startsWith("oi.")) {
                assertTrue(metricLine(rendered, name).contains("Binance 历史查询仅支持最近 30 天"), name);
            }
        }
        assertFalse(rendered.contains("provider="));
        assertFalse(rendered.contains("selector="));
    }

    @Test void exchangeLimitsStayScopedToTheirQueryShape() throws Exception {
        // 快照不限交易所、序列仅 Binance、统计仅 OKX，不能合并为指标级的交易所并集。
        var catalog = MetricCatalog.of(new ObjectMapper().readTree("""
                {"concepts":"测试", "operators":{}, "metrics":{
                  "test.metric":{"description":"测试指标", "unit":"quote", "bindings":{
                    "snapshot":{"provider":"test", "selector":"VALUE"},
                    "time_series":{"provider":"test", "selector":"VALUE", "exchanges":["binance"], "intervals":["5m"]},
                    "range_statistic":{"provider":"test", "selector":"VALUE", "exchanges":["okx"], "intervals":["1h"]}
                  }}
                }}
                """));
        String rendered = catalog.describeMetrics();
        assertTrue(rendered.contains("query_shape=snapshot；query_shape=time_series"));
        assertTrue(rendered.contains("query_shape=time_series（exchanges=[\"binance\"]；intervals=[\"5m\"]）"));
        assertTrue(rendered.contains("query_shape=range_statistic（exchanges=[\"okx\"]；intervals=[\"1h\"]）"));
        assertFalse(rendered.contains("[\"binance\",\"okx\"]"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"exchanges\":\"binance\"}", "{\"exchanges\":[1]}", "{\"exchanges\":[]}",
        "{\"intervals\":null}", "{\"intervals\":[\"5m\",false]}",
        "{\"depth\":{\"min\":50,\"max\":5}}",
        "{\"depth\":{\"min\":5,\"max\":50,\"default\":100}}",
        "{\"depth\":{\"min\":\"5\",\"max\":50}}",
        "{\"depth\":{\"min\":5,\"max\":4294967346}}",
        "{\"depth\":{\"max\":50}}", "{\"depth\":{\"min\":5,\"max\":50,\"default\":null}}",
        "{\"depth\":{\"min\":5,\"max\":50,\"defaut\":20}}"
    })
    void rejectsMalformedBindingsWhenLoading(String override) throws Exception {
        var json = new ObjectMapper();
        var document = json.createObjectNode();
        document.put("concepts", "test"); document.putObject("operators");
        var binding = document.putObject("metrics").putObject("test.metric")
                .put("description", "test").put("unit", "quote")
                .putObject("bindings").putObject("snapshot")
                .put("provider", "test").put("selector", "VALUE");
        binding.setAll((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(override));
        String error = assertThrows(IllegalArgumentException.class, () -> MetricCatalog.of(document)).getMessage();
        assertTrue(error.contains("metrics.test.metric.bindings.snapshot"), error);
    }

    @Test void parsedCatalogIsCachedImmutableAndIndependentOfInputJson() {
        var json = new ObjectMapper();
        var document = json.createObjectNode();
        document.put("concepts", "test"); document.putObject("operators");
        var binding = document.putObject("metrics").putObject("test.metric")
                .put("description", "test").put("unit", "quote")
                .putObject("bindings").putObject("snapshot")
                .put("provider", "test").put("selector", "VALUE");
        binding.putArray("exchanges").add("binance");
        var catalog = MetricCatalog.of(document);
        String rendered = catalog.describeMetrics();
        String version = catalog.version();
        var definition = catalog.metricDefinition("test.metric");
        binding.putArray("exchanges").add("okx");
        assertSame(definition, catalog.metricDefinition("test.metric"));
        assertEquals(rendered, catalog.describeMetrics());
        assertEquals(version, catalog.version());
        assertTrue(definition.bindings().get(QueryShape.SNAPSHOT).supportsExchange("binance"));
        assertFalse(definition.bindings().get(QueryShape.SNAPSHOT).supportsExchange("okx"));
        assertThrows(UnsupportedOperationException.class, () -> definition.bindings().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.bindings().get(QueryShape.SNAPSHOT).exchanges().add("okx"));
    }

    private static String metricLine(String rendered, String metric) {
        return rendered.lines().filter(line -> line.startsWith("- " + metric + ":"))
                .findFirst().orElseThrow();
    }
}
