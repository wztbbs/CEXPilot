package com.cexpilot.metric;

import com.cexpilot.calculation.AvgTool;
import com.cexpilot.market.kline.KlineQueryService;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.ToolDefinitionLoader;
import com.cexpilot.runtime.ToolOutputSchema;
import com.cexpilot.runtime.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 阶段 3a：snapshot 形态的编译准入、结果契约与输出边界；Provider 用桩，不接真实接口。 */
class SnapshotShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final String SNAPSHOT = QueryShape.SNAPSHOT.code();
    private static final String DAY = "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,"
            + "\"segment\":\"full\",\"extent\":\"full_period\"}";

    enum SnapshotSelector implements MetricSelector {
        VALUE;
        @Override public boolean supports(String shape) { return SNAPSHOT.equals(shape); }
    }

    private static MetricCatalog catalog() {
        ObjectNode document = JSON.createObjectNode();
        document.put("version", "test-snapshot-v1").put("concepts", "测试用概念");
        document.putObject("operators").put("avg", "测试算子");
        document.putObject("metrics").putObject("test.value").put("description", "测试快照指标")
                .put("unit", "quote").putObject("bindings")
                .putObject(SNAPSHOT).put("provider", "test_snapshot").put("selector", "VALUE");
        return MetricCatalog.of(document);
    }

    private static MetricProvider snapshotProvider() {
        return new MetricProvider() {
            public String name() { return "test_snapshot"; }
            public MetricSelector selector(String name) { return SnapshotSelector.valueOf(name); }
            public MetricResult query(MetricQuery query, RequestContext context) {
                return new MetricResult.Point(new MetricResult.SnapshotMetadata(context.requestTime(), context.userZone()),
                        new BigDecimal("104.5"));
            }
        };
    }

    private static MetricProviderRegistry providers() {
        return new MetricProviderRegistry(List.of(snapshotProvider()));
    }

    /** 只含 difference 的注册表：编译期校验需要工具存在，本形态不执行它。 */
    private static ToolRegistry registry() {
        var definitions = ToolDefinitionLoader.load(LOADER).stream()
                .filter(d -> d.name().equals("difference")).toList();
        return new ToolRegistry(List.of(new com.cexpilot.calculation.DifferenceTool()), definitions);
    }

    private static ObjectNode metric(String metric, String shape) {
        ObjectNode node = JSON.createObjectNode().put("id", "m1").put("metric", metric).put("query_shape", shape);
        node.putArray("exchanges").add("binance");
        node.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        return node;
    }

    private static ObjectNode plan(ObjectNode metric) {
        ObjectNode plan = JSON.createObjectNode();
        plan.putArray("metrics").add(metric);
        plan.putArray("calculations");
        return plan;
    }

    private MetricPlanCompiler compiler() {
        return new MetricPlanCompiler(catalog(), registry(), providers());
    }

    private MetricPlanCompiler realCompiler() {
        return new MetricPlanCompiler(new MetricCatalog(LOADER), registry(),
                new MetricProviderRegistry(List.of(new KlineMetricProvider(mock(KlineQueryService.class)))));
    }

    private static MetricBinding binding() {
        return new MetricBinding("m1", "test.value", "binance", SNAPSHOT,
                JSON.createObjectNode().put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT")
                        .put("settle", "USDT"), "USDT", "test_snapshot", SnapshotSelector.VALUE, "test-snapshot-v1");
    }

    @Test void snapshotCompilesWithoutTimeAndKeepsTypedQuery() {
        var nodes = compiler().compile(plan(metric("test.value", SNAPSHOT)), 8).nodes();
        assertEquals(1, nodes.size());
        var query = assertInstanceOf(SnapshotQuery.class, nodes.get(0).metricQuery());
        assertEquals("binance", query.exchange().displayName());
        assertEquals("BTC", query.base());
        assertEquals("USDT", nodes.get(0).metric().unit());
        assertEquals(SNAPSHOT, nodes.get(0).metric().shape());
        assertFalse(nodes.get(0).args().has("time"), "快照不得回显时间窗口");
        assertFalse(nodes.get(0).args().has("interval"));
    }

    @Test void snapshotRejectsWindowParametersExplicitly() {
        for (String field : List.of("time", "interval", "include_unclosed")) {
            var node = metric("test.value", SNAPSHOT);
            if ("time".equals(field)) {
                node.put("time", DAY);
            } else if ("interval".equals(field)) {
                node.put("interval", "1h");
            } else {
                node.put("include_unclosed", true);
            }
            var error = assertThrows(IllegalArgumentException.class, () -> compiler().compile(plan(node), 8));
            assertTrue(error.getMessage().contains(field), error.getMessage());
        }
    }

    @Test void rangeShapesStillRequireTimeAfterSchemaRelaxation() {
        for (String shape : List.of("range_statistic", "time_series")) {
            var node = metric("price.close", shape);
            var error = assertThrows(IllegalArgumentException.class, () -> realCompiler().compile(plan(node), 8));
            assertTrue(error.getMessage().contains("缺少非空字符串: type"), error.getMessage());
        }
    }

    @Test void snapshotOutputCarriesValueAndAsOfOnly() {
        var point = new MetricResult.Point(
                new MetricResult.SnapshotMetadata(NOW, ZoneId.of("Asia/Shanghai")), new BigDecimal("104.5"));
        var json = MetricResultJson.write(binding(), point);
        assertEquals(0, new BigDecimal("104.5").compareTo(json.get("value").decimalValue()));
        assertEquals("2026-09-29 20:00:00", json.path("as_of").asText());
        assertFalse(json.has("requested_range"));
        assertFalse(json.has("effective_range"));
        assertFalse(json.has("coverage"));
        assertFalse(json.has("observation_seconds"));
        assertFalse(json.has("statistics_omitted"));
        assertEquals("test_snapshot", json.at("/source/provider").asText());
        assertEquals("VALUE", json.at("/source/selector").asText());
        // 快照没有完整性标志，不能被算子的完整性检查拦掉
        assertDoesNotThrow(() -> new AvgTool().validateSource(json));
    }

    @Test void snapshotReferenceContractExposesValueOnly() {
        var schema = binding().outputSchema();
        assertNull(ToolOutputSchema.referenceError(schema, ".data.value"));
        assertNotNull(ToolOutputSchema.referenceError(schema, ".data.samples"));
        assertNotNull(ToolOutputSchema.referenceError(schema, ".data.observation_seconds"));
        assertNotNull(ToolOutputSchema.referenceError(schema, ".data.coverage.range_complete"));
    }

    @Test void plannerSchemaDeclaresSnapshotShapeWithoutGlobalTime() throws Exception {
        try (var input = LOADER.getResource("classpath:metrics/plan-schema.json").getInputStream()) {
            var schema = JSON.readTree(input);
            assertTrue(schema.at("/properties/plan/properties/metrics/items/properties/query_shape/enum")
                    .toString().contains("snapshot"));
            assertFalse(schema.at("/properties/plan/properties/metrics/items/required")
                    .toString().contains("time"), "time 不再是全局必填，由形态决定");
        }
    }
}
