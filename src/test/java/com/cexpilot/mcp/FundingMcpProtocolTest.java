package com.cexpilot.mcp;

import com.cexpilot.config.McpConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.mcp.server.FundingMcpServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 真实 HTTP MCP Client/Server，交易所客户端为 mock，不访问线上或交易所。 */
@SpringBootTest(classes = FundingMcpServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.file.name=${java.io.tmpdir}/cexpilot-mcp-protocol-test.log")
@ActiveProfiles("funding-mcp")
class FundingMcpProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL = "funding_rate_history";
    private static final Map<String, Object> ARGS = Map.of("symbol", "BTCUSDT",
            "startTimeMs", 1700000000000L, "endTimeMs", 1700100000000L, "limit", 100);

    @LocalServerPort
    private int port;

    @MockBean
    private BinanceClient binanceClient;

    @Autowired
    private McpSyncServer server;

    @Test
    void discoversOutputSchemaAndReceivesEquivalentStructuredAndTextResults() throws Exception {
        stubFunding();
        McpSyncClient client = rawClient();
        try {
            client.initialize();
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> TOOL.equals(t.name())).findFirst().orElseThrow();
            assertNotNull(tool.outputSchema());
            JsonNode schema = MAPPER.valueToTree(tool.outputSchema());
            assertEquals("array", schema.at("/properties/points/type").textValue());
            assertEquals("string", schema.at("/properties/points/items/properties/rate/type").textValue());

            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(TOOL, ARGS));
            assertFalse(Boolean.TRUE.equals(result.isError()));
            JsonNode body = MAPPER.valueToTree(result.structuredContent());
            assertEquals("0.0000123400", body.at("/points/0/rate").textValue());
            assertEquals(body, MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text()));
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void productionClientReadsStructuredResultOverHttp() {
        stubFunding();
        FundingMcpClient client = new FundingMcpClient(config());
        try {
            List<FundingRatePoint> points = client.fundingRateHistory("BTCUSDT", 1700000000000L,
                    1700100000000L, 100);
            assertEquals(List.of(new FundingRatePoint(new BigDecimal("0.0000123400"), 1700006400000L)), points);
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void productionClientRejectsWireResultAgainstDiscoveredSchema() {
        stubFunding();
        FundingMcpClient client = new FundingMcpClient(config());
        try {
            // 首次调用让生产 Client 从真实 tools/list 读取并缓存 Server 声明。
            client.fundingRateHistory("BTCUSDT", 1700000000000L, 1700100000000L, 100);
            McpSchema.Tool original = server.listTools().get(0);
            server.removeTool(TOOL);
            // 故意模拟服务端实现漂移：不声明 Schema 的 handler 绕过服务端校验，发出错误类型。
            server.addTool(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder().name(TOOL).inputSchema(original.inputSchema()).build())
                    .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                            .structuredContent(Map.of("exchange", "binance", "symbol", "BTCUSDT", "count", 1,
                                    "points", List.of(Map.of("rate", 0.0001, "fundingTime", 1700006400000L))))
                            .isError(false).build())
                    .build());

            ExchangeException error = assertThrows(ExchangeException.class,
                    () -> client.fundingRateHistory("BTCUSDT", 1700000000000L, 1700100000000L, 100));
            assertNotNull(error.getCause(), "应在 SDK 的 Schema 校验阶段失败");
            assertTrue(error.getCause().getMessage().contains("Tool call result validation failed"));
        } finally {
            client.shutdown();
        }
    }

    private void stubFunding() {
        when(binanceClient.fundingRateHistory(anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(new FundingRatePoint(new BigDecimal("0.0000123400"), 1700006400000L)));
    }

    private McpConfig config() {
        McpConfig config = new McpConfig();
        config.setBaseUrl("http://127.0.0.1:" + port);
        config.setRequestTimeoutMs(5000);
        return config;
    }

    private McpSyncClient rawClient() {
        var transport = HttpClientStreamableHttpTransport.builder(config().getBaseUrl())
                .jsonMapper(new JacksonMcpJsonMapper(MAPPER)).endpoint("/mcp").build();
        return McpClient.sync(transport).enableCallToolSchemaCaching(true)
                .requestTimeout(Duration.ofSeconds(5)).build();
    }
}
