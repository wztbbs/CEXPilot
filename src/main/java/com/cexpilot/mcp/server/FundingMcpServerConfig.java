package com.cexpilot.mcp.server;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.model.FundingRatePoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情 MCP Server 装配：Streamable HTTP 传输（POST/GET/DELETE /mcp）+
 * funding_rate_history 工具（薄封装 BinanceClient.fundingRateHistory）。
 * 分页、时间消解、覆盖核对留在主应用——Server 只对交易所原始端点负责。
 */
@Configuration
@Profile("funding-mcp")
public class FundingMcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(FundingMcpServerConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String TOOL_FUNDING_RATE_HISTORY = "funding_rate_history";
    static final String MCP_ENDPOINT = "/mcp";

    @Bean
    BinanceClient binanceClient(ExchangeConfig config) {
        return new BinanceClient(config);
    }

    @Bean
    WebMvcStreamableServerTransportProvider mcpTransportProvider(ObjectMapper objectMapper) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(MCP_ENDPOINT)
                .build();
    }

    /** MCP 端点路由：POST 收发 JSON-RPC，GET 建立 SSE 流，DELETE 关闭会话。 */
    @Bean
    RouterFunction<ServerResponse> mcpRouterFunction(WebMvcStreamableServerTransportProvider provider) {
        return provider.getRouterFunction();
    }

    @Bean
    McpSyncServer mcpServer(WebMvcStreamableServerTransportProvider provider, BinanceClient binanceClient) {
        return McpServer.sync(provider)
                .serverInfo("cexpilot-funding-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(fundingRateHistorySpec(binanceClient))
                .build();
    }

    static McpServerFeatures.SyncToolSpecification fundingRateHistorySpec(BinanceClient binanceClient) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("symbol", Map.of("type", "string", "minLength", 1,
                "pattern", "\\S", "description", "交易对，如 BTCUSDT"));
        properties.put("startTimeMs", timestampSchema("区间起点，毫秒时间戳（含）"));
        properties.put("endTimeMs", timestampSchema("区间终点，毫秒时间戳（含）"));
        properties.put("limit", Map.of("type", "integer",
                "minimum", 1, "maximum", 1000,
                "description", "返回条数上限，1~1000；截断时保留最早的 N 条，调用方负责翻页"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_FUNDING_RATE_HISTORY)
                .description("币安 USDⓈ-M 永续合约已结算资金费率历史（对应 GET /fapi/v1/fundingRate）："
                        + "返回结算时刻落在 [startTimeMs, endTimeMs] 内的费率，按 fundingTime 升序；"
                        + "rate 为小数字符串（0.0001 = 0.01%），fundingTime 为毫秒时间戳")
                .inputSchema(new McpSchema.JsonSchema("object", properties,
                        List.of("symbol", "startTimeMs", "endTimeMs", "limit"), false, null, null))
                .outputSchema(fundingRateHistoryOutputSchema())
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> callFundingRateHistory(binanceClient, request))
                .build();
    }

    /** 工具输出契约通过 tools/list 发布，客户端无需靠文本猜测结果结构。 */
    static Map<String, Object> fundingRateHistoryOutputSchema() {
        Map<String, Object> point = Map.of(
                "type", "object",
                "properties", Map.of(
                        "rate", Map.of("type", "string", "pattern", "^-?[0-9]+(\\.[0-9]+)?$",
                                "description", "十进制费率字符串，0.0001 = 0.01%，避免浮点精度损失"),
                        "fundingTime", timestampSchema("已结算资金费率的 Unix 毫秒时间戳")),
                "required", List.of("rate", "fundingTime"),
                "additionalProperties", false);
        return Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", Map.of(
                        "exchange", Map.of("type", "string", "const", "binance"),
                        "symbol", Map.of("type", "string", "minLength", 1),
                        "count", Map.of("type", "integer", "minimum", 0, "maximum", 1000),
                        "points", Map.of("type", "array", "items", point, "maxItems", 1000)),
                "required", List.of("exchange", "symbol", "count", "points"),
                "additionalProperties", false);
    }

    private static Map<String, Object> timestampSchema(String description) {
        return Map.of("type", "integer", "minimum", Long.MIN_VALUE, "maximum", Long.MAX_VALUE,
                "description", description);
    }

    /** 工具入参，已通过严格校验。 */
    record FundingArgs(String symbol, long startTimeMs, long endTimeMs, int limit) {
    }

    /**
     * 按 inputSchema 手工落实服务端校验（SDK 默认不强制 schema）：
     * 拒绝未知字段；整数参数只接受 JSON 整数（Jackson 把 1.9 解析成 Double，
     * 直接 longValue 会静默截断成 1，必须拒绝而非截断）。
     */
    static FundingArgs parseArgs(Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        java.util.Set<String> allowed = java.util.Set.of("symbol", "startTimeMs", "endTimeMs", "limit");
        for (String key : safeArgs.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("未知参数: " + key);
            }
        }
        String symbol = requireString(safeArgs, "symbol");
        long startTimeMs = requireLong(safeArgs, "startTimeMs");
        long endTimeMs = requireLong(safeArgs, "endTimeMs");
        long limit = requireLong(safeArgs, "limit");
        if (startTimeMs >= endTimeMs) {
            throw new IllegalArgumentException("startTimeMs 必须小于 endTimeMs");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1~1000，收到: " + limit);
        }
        return new FundingArgs(symbol, startTimeMs, endTimeMs, (int) limit);
    }

    /**
     * 工具实现。业务错误（参数非法、上游交易所失败）走 isError=true 而不是协议层
     * JSON-RPC error：调用方需要区分"Server 坏了"和"这次调用没成功"。
     */
    private static McpSchema.CallToolResult callFundingRateHistory(BinanceClient client,
                                                                   McpSchema.CallToolRequest request) {
        long start = System.currentTimeMillis();
        try {
            FundingArgs parsed = parseArgs(request.arguments());
            List<FundingRatePoint> points = client.fundingRateHistory(
                    parsed.symbol(), parsed.startTimeMs(), parsed.endTimeMs(), parsed.limit());
            ObjectNode body = MAPPER.createObjectNode();
            body.put("exchange", "binance");
            body.put("symbol", parsed.symbol());
            body.put("count", points.size());
            ArrayNode arr = body.putArray("points");
            for (FundingRatePoint p : points) {
                ObjectNode item = arr.addObject();
                item.put("rate", p.rate().toPlainString());
                item.put("fundingTime", p.fundingTime());
            }
            log.info("mcp tools/call {} symbol={} [{},{}] limit={} -> {} 条 {}ms",
                    TOOL_FUNDING_RATE_HISTORY, parsed.symbol(), parsed.startTimeMs(), parsed.endTimeMs(),
                    parsed.limit(), points.size(), System.currentTimeMillis() - start);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(body)
                    // MCP 建议同时提供等价的文本 JSON，兼容只读取 content 的客户端。
                    .addTextContent(body.toString())
                    .isError(false)
                    .build();
        } catch (Exception e) {
            log.warn("mcp tools/call {} 失败 {}ms: {}",
                    TOOL_FUNDING_RATE_HISTORY, System.currentTimeMillis() - start, e.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private static String requireString(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("参数 " + field + " 必须是非空字符串");
        }
        return s;
    }

    /** 只接受 JSON 整数（Jackson 解析为 Integer/Long/BigInteger）；Double 拒绝，BigInteger 越界拒绝。 */
    private static long requireLong(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof java.math.BigInteger big) {
            try {
                // longValue() 对越界值静默回绕（如 18446744073709551617 → 1），必须精确转换
                return big.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("参数 " + field + " 超出 long 范围: " + big);
            }
        }
        throw new IllegalArgumentException("参数 " + field + " 必须是整数（毫秒时间戳）");
    }
}
