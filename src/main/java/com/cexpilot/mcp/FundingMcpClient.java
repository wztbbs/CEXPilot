package com.cexpilot.mcp;

import com.cexpilot.config.McpConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.runtime.NodeTraceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 行情 MCP Server 的调用方（Streamable HTTP 传输）。对外暴露与 BinanceClient
 * 同形的方法签名，对内做三件事：懒初始化 MCP 会话（initialize 握手）、
 * 把每次 tools/call 以 TOOL_CALL 事件落进当前节点的 trace（input/output 必须
 * 是合法 JSON——落库走 CAST(? AS JSON)，非法 JSON 会静默丢事件）、按错误语义分层：
 * 调用失败（连接拒绝、超时、协议错误）→ 记录并抛 ExchangeException，保留共享 Client；
 * 业务错误（isError=true，如参数非法、上游交易所失败）→ 协议层成功，保留会话直接抛。
 */
@Component
@ConditionalOnProperty(name = "mcp.funding.transport", havingValue = "mcp")
public class FundingMcpClient {

    static final String TOOL_FUNDING_RATE_HISTORY = "funding_rate_history";
    private static final String TRACE_TOOL_NAME = "mcp." + TOOL_FUNDING_RATE_HISTORY;
    private static final String NAME = "mcp";
    private static final Logger log = LoggerFactory.getLogger(FundingMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpConfig config;
    private final ClientFactory clientFactory;
    private volatile McpSyncClient client;

    @Autowired
    public FundingMcpClient(McpConfig config) {
        this(config, null);
    }

    /** 测试 seam：替换建连过程（握手失败的资源释放路径需要注入 fake 验证）。 */
    FundingMcpClient(McpConfig config, ClientFactory clientFactory) {
        this.config = config;
        this.clientFactory = clientFactory;
    }

    /** 建连工厂；null 表示走真实 Streamable HTTP 传输。 */
    interface ClientFactory {
        McpSyncClient create();
    }

    @PreDestroy
    void shutdown() {
        McpSyncClient current;
        synchronized (this) {
            current = client;
            client = null;
        }
        closeQuietly(current);
    }

    /** 与 BinanceClient.fundingRateHistory 同签名：MCP 化前后上层零感知。 */
    public List<FundingRatePoint> fundingRateHistory(String symbol, long startTimeMs, long endTimeMs, int limit) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("symbol", symbol);
        arguments.put("startTimeMs", startTimeMs);
        arguments.put("endTimeMs", endTimeMs);
        arguments.put("limit", limit);
        String inputJson = "{\"tool\":\"" + TOOL_FUNDING_RATE_HISTORY + "\",\"arguments\":"
                + writeJson(arguments) + "}";
        long start = System.currentTimeMillis();

        McpSchema.CallToolResult result;
        try {
            McpSyncClient session = client();
            result = session.callTool(new McpSchema.CallToolRequest(TOOL_FUNDING_RATE_HISTORY, arguments));
        } catch (Exception e) {
            // 单次调用失败不代表整个 Client 已失效；保留共享实例，避免关闭其他并发调用。
            record(inputJson, null, System.currentTimeMillis() - start, e.getMessage());
            throw new ExchangeException(NAME, "调用行情 MCP Server 失败（" + config.getBaseUrl() + "）: " + e.getMessage(), e);
        }
        long durationMs = System.currentTimeMillis() - start;
        if (result != null && Boolean.TRUE.equals(result.isError())) {
            // 业务错误：协议层成功，会话保留。错误文本包装成合法 JSON 再落 trace
            String text = errorText(result);
            record(inputJson, errorJson(text), durationMs, abbreviate(text));
            throw new ExchangeException(NAME, "行情 MCP Server 工具执行失败: " + abbreviate(text));
        }
        String outputJson = null;
        try {
            JsonNode body = structuredBody(result);
            outputJson = body.toString();
            List<FundingRatePoint> points = parsePoints(body, symbol, limit);
            record(inputJson, outputJson, durationMs, null);
            return points;
        } catch (ExchangeException e) {
            record(inputJson, outputJson == null ? errorJson(e.getMessage()) : outputJson,
                    durationMs, e.getMessage());
            throw e;
        }
    }

    /** 成功结果从 MCP 标准 structuredContent 读取，不猜测某个文本块是否为 JSON。 */
    static JsonNode structuredBody(McpSchema.CallToolResult result) {
        if (result == null || result.structuredContent() == null) {
            throw new ExchangeException(NAME, "MCP 成功响应缺少 structuredContent");
        }
        JsonNode body;
        try {
            body = MAPPER.valueToTree(result.structuredContent());
        } catch (IllegalArgumentException e) {
            throw new ExchangeException(NAME, "MCP structuredContent 无法转换为 JSON 对象", e);
        }
        if (!body.isObject()) {
            throw new ExchangeException(NAME, "MCP structuredContent 必须是 JSON 对象");
        }
        return body;
    }

    /** SDK 按远端 outputSchema 校验结构；这里负责资金费率领域映射与跨字段一致性。 */
    static List<FundingRatePoint> parsePoints(JsonNode root, String expectedSymbol, int limit) {
        if (!"binance".equals(root.path("exchange").asText())) {
            throw new ExchangeException(NAME, "MCP 响应 exchange 必须是 binance");
        }
        if (!root.path("symbol").isTextual() || !expectedSymbol.equals(root.path("symbol").textValue())) {
            throw new ExchangeException(NAME, "MCP 响应 symbol 与请求不一致: " + root.path("symbol"));
        }
        JsonNode points = root.path("points");
        if (!points.isArray()) {
            throw new ExchangeException(NAME, "MCP 响应缺少 points 数组");
        }
        JsonNode count = root.path("count");
        if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() != points.size()
                || points.size() > limit) {
            throw new ExchangeException(NAME, "MCP 响应 count、points 数量或请求 limit 不一致");
        }
        List<FundingRatePoint> rates = new ArrayList<>();
        for (JsonNode item : points) {
            String rate = item.path("rate").textValue();
            if (rate == null || rate.isBlank()) {
                throw new ExchangeException(NAME, "MCP 响应 rate 必须是非空十进制字符串");
            }
            JsonNode fundingTime = item.get("fundingTime");
            // longValue() 对 BigInteger 越界值静默回绕，必须显式排除
            if (fundingTime == null || !fundingTime.isIntegralNumber() || !fundingTime.canConvertToLong()) {
                throw new ExchangeException(NAME, "MCP 响应 fundingTime 缺失、非整数或超出 long 范围");
            }
            BigDecimal value;
            try {
                value = new BigDecimal(rate);
            } catch (NumberFormatException e) {
                throw new ExchangeException(NAME, "MCP 响应 rate 不是合法小数: " + rate, e);
            }
            rates.add(new FundingRatePoint(value, fundingTime.longValue()));
        }
        return rates;
    }

    /** 懒初始化：首次调用才握手，Server 未启动不拖垮主应用启动；握手失败下次重试。 */
    private McpSyncClient client() {
        McpSyncClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = connect();
                    client = current;
                }
            }
        }
        return current;
    }

    private McpSyncClient connect() {
        log.info("MCP 建连 {} {}", config.getBaseUrl(), config.getEndpoint());
        McpSyncClient newClient = clientFactory != null ? clientFactory.create() : buildClient();
        try {
            McpSchema.InitializeResult init = newClient.initialize();
            log.info("MCP 握手成功 server={} {} 协议版本={}",
                    init.serverInfo().name(), init.serverInfo().version(), init.protocolVersion());
            return newClient;
        } catch (RuntimeException e) {
            // 握手失败：实例尚未进入共享字段，调用方与 shutdown 都够不到它，必须就地关闭，
            // 否则 Server 持续故障期间每次重试都泄漏一个未释放的连接
            closeQuietly(newClient);
            throw e;
        }
    }

    private McpSyncClient buildClient() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(config.getBaseUrl())
                .endpoint(config.getEndpoint())
                .jsonMapper(new JacksonMcpJsonMapper(MAPPER))
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                // 初始化时发现并缓存远端 outputSchema，callTool 返回前由 SDK 校验 structuredContent。
                .enableCallToolSchemaCaching(true)
                .clientInfo(new McpSchema.Implementation("cexpilot", "0.1.0"))
                .build();
    }

    private static void closeQuietly(McpSyncClient target) {
        if (target != null) {
            try {
                target.closeGracefully();
            } catch (Exception e) {
                log.debug("关闭 MCP 会话异常（忽略）: {}", e.getMessage());
            }
        }
    }

    private static void record(String inputJson, String outputJson, long durationMs, String error) {
        NodeTraceCollector.record(TRACE_TOOL_NAME, inputJson, outputJson, durationMs, error);
    }

    /** 业务错误文本包装成合法 JSON：output_json 列 CAST(? AS JSON)，裸文本会落库失败丢事件。 */
    private static String errorJson(String message) {
        try {
            return MAPPER.createObjectNode()
                    .put("isError", true)
                    .put("message", abbreviate(message))
                    .toString();
        } catch (Exception e) {
            return "{\"isError\":true}";
        }
    }

    private static String errorText(McpSchema.CallToolResult result) {
        String text = result.content() == null ? "" : result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
        return text.isBlank() ? "Server 未提供错误详情" : text;
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 MCP 参数失败", e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "...";
    }
}
