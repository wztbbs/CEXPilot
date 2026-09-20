package com.cexpilot.runtime;

import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.dag.DagPlan;
import com.cexpilot.dag.DagPlanner;
import com.cexpilot.dag.PlanValidator;
import com.cexpilot.ethereum.TxAnalysisService;
import com.cexpilot.ethereum.tool.GetTransactionTool;
import com.cexpilot.intent.IntentRegistry;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.tool.*;
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

        List<ChatMessage> seen = new ArrayList<>();
        var loader = new DefaultResourceLoader();
        DagPlanner planner = new DagPlanner((messages, tools) -> {
            seen.addAll(messages);
            assertNull(tools);
            return new ChatResponse("{\"in_domain\":true,\"intent\":\"UNKNOWN\",\"plan\":null}", List.of(), 1, 1);
        }, registry, new IntentRegistry(loader), new LlmConfig(), new DagConfig(),
                new PromptStore(loader), new PlanValidator(registry, new DagConfig()));
        planner.plan("查询BTC", "", "test", event -> {});
        String prompt = seen.get(0).content();
        assertTrue(prompt.contains("sample：YAML中的新描述"));
        assertTrue(prompt.contains("default=binance"));
        assertTrue(prompt.contains("minimum=1, maximum=5"));
        assertFalse(prompt.contains("仅供文档的内容"));
        assertFalse(prompt.contains("不会自动注入的限制"));

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
        List<AgentTool> executors = List.of(new GetTickerTool(market), new GetKlinesTool(market),
                new GetFundingRateTool(market), new GetOpenInterestTool(market), new GetMarkPriceTool(market),
                new GetOrderbookTool(market), new GetRecentTradesTool(market), new CompareExchangesTool(market),
                new GetTransactionTool(mock(TxAnalysisService.class)));
        try (var context = new AnnotationConfigApplicationContext()) {
            for (AgentTool executor : executors) {
                context.registerBean(executor.name(), AgentTool.class, () -> executor);
            }
            context.register(ToolRegistry.class);
            context.refresh();
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertEquals(9, registry.size());
            assertEquals("binance", registry.prepareArguments("get_ticker", MAPPER.createObjectNode().put("symbol", "BTC")).path("exchange").asText());
            assertThrows(IllegalArgumentException.class, () -> registry.prepareArguments("get_transaction", MAPPER.createObjectNode().put("tx_hash", "0xabc")));
            assertDoesNotThrow(() -> registry.prepareArguments("get_transaction", MAPPER.createObjectNode().put("tx_hash", "0x" + "a".repeat(64))));
            assertEquals("1h", registry.prepareArguments("compare_exchanges", MAPPER.createObjectNode().put("symbol", "ETH")).path("window").asText());
        }
    }
    @Test
    void configuredDefaultsReachRealToolExecution() {
        MarketDataService market = mock(MarketDataService.class);
        org.mockito.Mockito.when(market.recentTrades(com.cexpilot.market.Exchange.BINANCE, "BTC", 50))
                .thenReturn(List.of());
        var definitions = ToolDefinitionLoader.load(new DefaultResourceLoader()).stream()
                .filter(definition -> definition.name().equals("get_recent_trades")).toList();
        AgentTool executor = new GetRecentTradesTool(market);
        ToolRegistry registry = new ToolRegistry(List.of(executor), definitions);
        var args = registry.prepareArguments(executor.name(), MAPPER.createObjectNode().put("symbol", "BTC"));
        ToolResult result = executor.execute(args, new ToolContext("test", null));
        assertTrue(result.ok());
        assertTrue(result.data().path("recent_trades_columns").isArray());
        org.mockito.Mockito.verify(market).recentTrades(com.cexpilot.market.Exchange.BINANCE, "BTC", 50);
    }
}
