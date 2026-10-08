package com.cexpilot.runtime;

import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.dag.DagPlan;
import com.cexpilot.dag.DagPlanner;
import com.cexpilot.dag.PlanValidator;
import com.cexpilot.ethereum.TxAnalysisService;
import com.cexpilot.ethereum.tool.GetTransactionTool;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.prompt.PromptStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ToolRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String YAML = """
            name: sample
            enabled: true
            description: YAML中的新描述
            capabilities: [仅供文档的内容]
            limitations: [不会自动注入的限制]
            output_schema:
              type: object
              additionalProperties: false
              properties:
                price: {type: number, description: 最新价格}
            input_schema:
              type: object
              additionalProperties: false
              required: [symbol]
              properties:
                symbol: {type: string, description: 基础币代码}
                exchange: {type: string, enum: [binance, okx], default: binance}
                limit: {type: integer, default: 3, minimum: 1, maximum: 5}
            """;

    private static PathMatchingResourcePatternResolver yamlLoader(String... documents) {
        return new PathMatchingResourcePatternResolver() {
            @Override
            public Resource[] getResources(String pattern) {
                return java.util.Arrays.stream(documents)
                        .map(doc -> new ByteArrayResource(doc.getBytes(StandardCharsets.UTF_8)))
                        .toArray(Resource[]::new);
            }
        };
    }

    private static AgentTool executor(String name) {
        return new AgentTool() {
            public String name() { return name; }
            public ToolResult execute(JsonNode args, ToolContext ctx) { return ToolResult.success(args); }
        };
    }

    @Test
    void yamlControlsDescriptionSchemaPromptValidationAndDefaults() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(executor("sample")), yamlLoader(YAML));
        assertEquals("YAML中的新描述", registry.spec("sample").description());
        JsonNode input = MAPPER.readTree("{\"symbol\":\"BTC\"}");
        JsonNode resolved = registry.prepareArguments("sample", input);
        assertEquals("binance", resolved.path("exchange").asText());
        assertEquals(3, resolved.path("limit").asInt());
        assertFalse(input.has("limit"));
        assertEquals(resolved, registry.get("sample").execute(resolved, new ToolContext("test", null)).data());

        // Planner 已不暴露取数 Tool；契约仍供物理 DAG 校验与默认值处理使用。
        assertEquals("{price:number(最新价格)}", ToolOutputSchema.describe(registry.outputSchema("sample")));

        var validator = new PlanValidator(registry, new DagConfig());
        DagPlan valid = DagPlan.fromJson(MAPPER.readTree("""
                {"nodes":[{"id":"n1","tool":"sample","args":{"symbol":"BTC"}}]}
                """));
        assertTrue(validator.validate(valid, null, 8).isEmpty());
        DagPlan invalid = DagPlan.fromJson(MAPPER.readTree("""
                {"nodes":[{"id":"n1","tool":"sample","args":{"symbol":"BTC","limit":6}}]}
                """));
        assertTrue(validator.validate(invalid, null, 8).stream().anyMatch(e -> e.contains("maximum")));

        ToolRegistry changed = new ToolRegistry(List.of(executor("sample")), yamlLoader(
                YAML.replace("YAML中的新描述", "已修改描述").replace("default: 3", "default: 4").replace("maximum: 5", "maximum: 7")));
        assertEquals("已修改描述", changed.spec("sample").description());
        assertEquals(4, changed.prepareArguments("sample", input).path("limit").asInt());
        assertTrue(new PlanValidator(changed, new DagConfig()).validate(invalid, null, 8).isEmpty());
    }

    @Test
    void disabledToolIsNeitherVisibleNorExecutable() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(executor("sample")), yamlLoader(YAML.replace("enabled: true", "enabled: false")));
        assertEquals(0, registry.size());
        assertTrue(registry.specs().isEmpty());
        assertNull(registry.get("sample"));
        assertThrows(IllegalArgumentException.class, () -> registry.prepareArguments("sample", MAPPER.createObjectNode()));
        DagPlan plan = DagPlan.fromJson(MAPPER.readTree("{\"nodes\":[{\"id\":\"n1\",\"tool\":\"sample\",\"args\":{}}]}"));
        assertFalse(new PlanValidator(registry, new DagConfig()).validate(plan, null, 8).isEmpty());
    }

    @Test
    void duplicateMissingAndUnboundConfigurationFailStartup() {
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(executor("sample")), yamlLoader(YAML, YAML)));
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(executor("sample")), yamlLoader()));
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(), yamlLoader(YAML)));
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(executor("sample"), executor("sample")), yamlLoader(YAML)));
    }

    @Test
    void invalidYamlSchemaAndDefaultFailStartup() {
        for (String bad : List.of(
                YAML.replace("default: 3", "default: 9"),
                YAML.replace("required: [symbol]", "required: [unknown]"),
                YAML.replace("type: integer", "type: imaginary"),
                YAML.replace("output_schema:", "ignored_output_schema:"),
                YAML.replace("price: {type: number, description: 最新价格}", "price: {type: imaginary}"),
                YAML.replace("enabled: true", "enabled: maybe"),
                YAML.replace("minimum: 1", "minimum: 1, unsupported: true"),
                YAML + "enabled: false\n")) {
            assertThrows(IllegalStateException.class, () -> ToolDefinitionLoader.load(yamlLoader(bad)));
        }
    }

    @Test
    void validatesTypesEnumsBoundsUnknownFieldsAndResolvedReferences() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(executor("sample")), yamlLoader(YAML));
        for (String args : List.of(
                "{\"symbol\":\"BTC\",\"exchange\":\"unknown\"}",
                "{\"symbol\":\"BTC\",\"limit\":\"3\"}",
                "{\"symbol\":\"BTC\",\"limit\":0}",
                "{\"symbol\":\"BTC\",\"limit\":6}",
                "{\"symbol\":\"BTC\",\"extra\":1}",
                "{\"symbol\":null}")) {
            assertThrows(IllegalArgumentException.class, () -> registry.prepareArguments("sample", MAPPER.readTree(args)));
        }
        JsonNode reference = MAPPER.readTree("{\"symbol\":\"BTC\",\"limit\":\"{{n1.data.count}}\"}");
        assertTrue(ToolArguments.validate(reference, registry.spec("sample").inputSchema(), true).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.prepareArguments("sample", reference));
    }

    @Test
    void allRealToolExecutorsBindToYamlThroughSpring() {
        MarketDataService market = mock(MarketDataService.class);
        List<AgentTool> executors = List.of(
                new com.cexpilot.calculation.AvgTool(), new com.cexpilot.calculation.RelativeChangeTool(),
                new com.cexpilot.calculation.AnnualizeTool(), new com.cexpilot.calculation.CompareTool(),
                new com.cexpilot.calculation.DifferenceTool(), new com.cexpilot.calculation.RatioTool(),
                new com.cexpilot.calculation.SumTool(), new com.cexpilot.calculation.MinTool(),
                new com.cexpilot.calculation.MaxTool(),
                new GetTransactionTool(mock(TxAnalysisService.class)));
        try (var context = new AnnotationConfigApplicationContext()) {
            for (AgentTool executor : executors) {
                context.registerBean(executor.name(), AgentTool.class, () -> executor);
            }
            context.register(ToolRegistry.class, com.cexpilot.metric.MetricCatalog.class,
                    DagPlanner.class, PlanValidator.class, DagConfig.class, LlmConfig.class,
                    PromptStore.class, com.cexpilot.dag.DagExecutor.class,
                    com.cexpilot.metric.MetricProviderRegistry.class, com.cexpilot.metric.KlineMetricProvider.class,
                    com.cexpilot.metric.OiMetricProvider.class, com.cexpilot.metric.MarkPriceMetricProvider.class,
                    com.cexpilot.metric.TakerMetricProvider.class, com.cexpilot.metric.TickerMetricProvider.class,
                    com.cexpilot.metric.OrderBookMetricProvider.class, com.cexpilot.metric.FundingMetricProvider.class);
            context.registerBean(com.cexpilot.market.MarketDataService.class,
                    () -> mock(com.cexpilot.market.MarketDataService.class));
            context.registerBean(com.cexpilot.market.kline.KlineQueryService.class,
                    () -> mock(com.cexpilot.market.kline.KlineQueryService.class));
            context.registerBean(com.cexpilot.market.oi.OiQueryService.class,
                    () -> mock(com.cexpilot.market.oi.OiQueryService.class));
            context.registerBean(com.cexpilot.market.markprice.MarkPriceQueryService.class,
                    () -> mock(com.cexpilot.market.markprice.MarkPriceQueryService.class));
            context.registerBean(com.cexpilot.market.taker.TakerVolumeQueryService.class,
                    () -> mock(com.cexpilot.market.taker.TakerVolumeQueryService.class));
            context.registerBean(com.cexpilot.market.funding.FundingQueryService.class,
                    () -> mock(com.cexpilot.market.funding.FundingQueryService.class));
            context.registerBean(com.cexpilot.llm.LlmClient.class, () -> mock(com.cexpilot.llm.LlmClient.class));
            context.refresh();
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertEquals(10, registry.size());
            assertNotNull(context.getBean(DagPlanner.class));
            assertNotNull(context.getBean(com.cexpilot.dag.DagExecutor.class));
            assertNotNull(context.getBean(com.cexpilot.metric.MetricProviderRegistry.class).get("kline"));
            assertNotNull(context.getBean(com.cexpilot.metric.MetricProviderRegistry.class).get("ticker"),
                    "目录含快照指标后，ticker Provider 必须注册，否则启动校验失败");
            assertThrows(IllegalArgumentException.class, () -> registry.prepareArguments("get_transaction", MAPPER.createObjectNode().put("tx_hash", "0xabc")));
            assertDoesNotThrow(() -> registry.prepareArguments("get_transaction", MAPPER.createObjectNode().put("tx_hash", "0x" + "a".repeat(64))));
        }
    }
}
