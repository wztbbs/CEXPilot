package com.cexpilot.mcp.server;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.model.FundingRatePoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 工具入参的服务端校验：inputSchema 只是声明，SDK 默认不强制，
 * 服务端必须自己落实——拒绝未知字段、拒绝小数（longValue 会静默截断）。
 */
class FundingMcpServerConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultJsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(MAPPER);

    @Test
    void publishesMachineReadableInputAndOutputConstraints() {
        var tool = FundingMcpServerConfig.fundingRateHistorySpec(mock(BinanceClient.class)).tool();
        assertNotNull(tool.outputSchema());
        Map<String, Object> inputSchema = MAPPER.convertValue(tool.inputSchema(), new TypeReference<>() { });
        assertTrue(VALIDATOR.validate(inputSchema, validArgs()).valid());
        Map<String, Object> args = validArgs();
        args.put("limit", 1001);
        assertFalse(VALIDATOR.validate(inputSchema, args).valid());
        args.put("limit", 1);
        args.put("symbol", "   ");
        assertFalse(VALIDATOR.validate(inputSchema, args).valid());
    }

    @Test
    void returnsStructuredContentAndEquivalentCompatibilityText() throws Exception {
        BinanceClient client = mock(BinanceClient.class);
        when(client.fundingRateHistory("BTCUSDT", 1791726707000L, 1791985907000L, 100))
                .thenReturn(List.of(new FundingRatePoint(new BigDecimal("0.0000123400"), 1791800000000L)));
        var spec = FundingMcpServerConfig.fundingRateHistorySpec(client);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("funding_rate_history", validArgs()));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.valueToTree(result.structuredContent());
        assertEquals("0.0000123400", body.path("points").get(0).path("rate").textValue());
        assertEquals(1, body.path("count").intValue());
        assertEquals(body, MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text()));
        assertTrue(VALIDATOR.validate(spec.tool().outputSchema(), result.structuredContent()).valid());
    }

    @Test
    void outputSchemaRejectsMalformedData() {
        ObjectNode valid = MAPPER.createObjectNode();
        valid.put("exchange", "binance");
        valid.put("symbol", "BTCUSDT");
        valid.put("count", 1);
        valid.putArray("points").addObject().put("rate", "0.0001").put("fundingTime", 1791800000000L);
        Map<String, Object> schema = FundingMcpServerConfig.fundingRateHistoryOutputSchema();
        assertTrue(VALIDATOR.validate(schema, valid).valid());

        ObjectNode numericRate = valid.deepCopy();
        ((ObjectNode) numericRate.withArray("points").get(0)).put("rate", 0.0001);
        ObjectNode fractionalTime = valid.deepCopy();
        ((ObjectNode) fractionalTime.withArray("points").get(0)).put("fundingTime", 1.9);
        ObjectNode overflowTime = valid.deepCopy();
        ((ObjectNode) overflowTime.withArray("points").get(0))
                .put("fundingTime", new BigInteger("18446744073709551617"));
        ObjectNode missingPoints = valid.deepCopy();
        missingPoints.remove("points");
        ObjectNode unknownField = valid.deepCopy().put("unexpected", true);
        ObjectNode invalidRate = valid.deepCopy();
        ((ObjectNode) invalidRate.withArray("points").get(0)).put("rate", "not-a-rate");
        for (ObjectNode body : List.of(numericRate, fractionalTime, overflowTime,
                missingPoints, unknownField, invalidRate)) {
            assertFalse(VALIDATOR.validate(schema, body).valid(), body.toString());
        }
    }

    private static Map<String, Object> validArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("symbol", "BTCUSDT");
        args.put("startTimeMs", 1791726707000L);
        args.put("endTimeMs", 1791985907000L);
        args.put("limit", 100);
        return args;
    }

    @Test
    void acceptsValidArgs() {
        FundingMcpServerConfig.FundingArgs parsed = FundingMcpServerConfig.parseArgs(validArgs());
        assertEquals("BTCUSDT", parsed.symbol());
        assertEquals(100, parsed.limit());
    }

    @Test
    void rejectsFractionalNumberInsteadOfTruncating() {
        Map<String, Object> args = validArgs();
        args.put("limit", 1.9); // Jackson 解析为 Double；longValue() 会静默截断成 1
        assertThrows(IllegalArgumentException.class, () -> FundingMcpServerConfig.parseArgs(args));
    }

    @Test
    void rejectsUnknownFields() {
        Map<String, Object> args = validArgs();
        args.put("foo", 1); // schema 声明 additionalProperties=false，服务端必须落实
        assertThrows(IllegalArgumentException.class, () -> FundingMcpServerConfig.parseArgs(args));
    }

    @Test
    void rejectsReversedRange() {
        Map<String, Object> args = validArgs();
        args.put("startTimeMs", 1791985907000L);
        args.put("endTimeMs", 1791726707000L);
        assertThrows(IllegalArgumentException.class, () -> FundingMcpServerConfig.parseArgs(args));
    }

    @Test
    void rejectsOutOfRangeLimit() {
        Map<String, Object> args = validArgs();
        args.put("limit", 0);
        assertThrows(IllegalArgumentException.class, () -> FundingMcpServerConfig.parseArgs(args));
        args.put("limit", 1001);
        assertThrows(IllegalArgumentException.class, () -> FundingMcpServerConfig.parseArgs(args));
    }

    @Test
    void rejectsIntegerBeyondLongInsteadOfWrapping() {
        // longValue() 会把 18446744073709551617 静默回绕成 1
        Map<String, Object> args = validArgs();
        args.put("limit", new java.math.BigInteger("18446744073709551617"));
        assertThrows(IllegalArgumentException.class, () -> FundingMcpServerConfig.parseArgs(args));
    }

    @Test
    void acceptsLongBoundaryValues() {
        Map<String, Object> args = validArgs();
        args.put("startTimeMs", 1L);
        args.put("endTimeMs", Long.MAX_VALUE);
        FundingMcpServerConfig.FundingArgs parsed = FundingMcpServerConfig.parseArgs(args);
        assertEquals(Long.MAX_VALUE, parsed.endTimeMs());
    }
}
