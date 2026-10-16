package com.cexpilot.mcp;

import com.cexpilot.config.McpConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.runtime.NodeTraceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FundingMcpClient 响应解析与生命周期：失败调用保留共享实例，不关闭其他在途调用。
 */
class FundingMcpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesPointsPreservingRatePrecision() {
        String json = "{\"exchange\":\"binance\",\"symbol\":\"BTCUSDT\",\"count\":2,"
                + "\"points\":[{\"rate\":\"0.00001234\",\"fundingTime\":1700006400000},"
                + "{\"rate\":\"-0.0001\",\"fundingTime\":1700035200000}]}";
        List<FundingRatePoint> points = FundingMcpClient.parsePoints(json(json), "BTCUSDT", 100);
        assertEquals(2, points.size());
        // 字符串解析，不能丢精度
        assertEquals(new BigDecimal("0.00001234"), points.get(0).rate());
        assertEquals(new BigDecimal("-0.0001"), points.get(1).rate());
        assertEquals(1700006400000L, points.get(0).fundingTime());
    }

    @Test
    void rejectsTextOnlySuccessInsteadOfGuessingJson() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent(validBody("BTCUSDT").toString()).isError(false).build();
        assertThrows(ExchangeException.class, () -> FundingMcpClient.structuredBody(result));
    }

    @Test
    void rejectsNonObjectStructuredContent() {
        for (Object value : List.of(List.of(1, 2), "{\"points\":[]}")) {
            McpSchema.CallToolResult result = McpSchema.CallToolResult.builder().structuredContent(value).build();
            assertThrows(ExchangeException.class, () -> FundingMcpClient.structuredBody(result));
        }
    }

    @Test
    void rejectsMissingPointsArray() {
        ObjectNode body = validBody("BTCUSDT");
        body.remove("points");
        assertThrows(ExchangeException.class, () -> FundingMcpClient.parsePoints(body, "BTCUSDT", 100));
    }

    @Test
    void rejectsMissingRateField() {
        assertThrows(ExchangeException.class,
                () -> parseSamplePoints("[{\"fundingTime\":1}]"));
    }

    @Test
    void rejectsMissingFundingTime() {
        // 缺失时 asLong(0) 会静默变成 1970 的结算点，必须响亮失败
        assertThrows(ExchangeException.class,
                () -> parseSamplePoints("[{\"rate\":\"0.0001\"}]"));
    }

    @Test
    void rejectsNonIntegralFundingTime() {
        assertThrows(ExchangeException.class,
                () -> parseSamplePoints("[{\"rate\":\"0.0001\",\"fundingTime\":1.9}]"));
    }

    @Test
    void rejectsNonDecimalRate() {
        assertThrows(ExchangeException.class,
                () -> parseSamplePoints("[{\"rate\":\"abc\",\"fundingTime\":1}]"));
    }

    @Test
    void rejectsFundingTimeBeyondLong() {
        // 超出 long 的整数：longValue() 会静默回绕成正常时间戳
        assertThrows(ExchangeException.class,
                () -> parseSamplePoints("[{\"rate\":\"0.0001\",\"fundingTime\":18446744073709551617}]"));
    }

    @Test
    void rejectsNumericRateInsteadOfCoercingToString() {
        assertThrows(ExchangeException.class,
                () -> parseSamplePoints("[{\"rate\":0.0001,\"fundingTime\":1}]"));
    }

    @Test
    void rejectsResponseForDifferentExchangeOrSymbol() {
        ObjectNode body = validBody("ETHUSDT");
        assertThrows(ExchangeException.class, () -> FundingMcpClient.parsePoints(body, "BTCUSDT", 100));
        body.put("symbol", "BTCUSDT");
        body.put("exchange", "okx");
        assertThrows(ExchangeException.class, () -> FundingMcpClient.parsePoints(body, "BTCUSDT", 100));
    }

    @Test
    void rejectsCountMismatchAndTooManyPointsForRequestedLimit() {
        ObjectNode body = validBody("BTCUSDT");
        body.put("count", 2);
        assertThrows(ExchangeException.class, () -> FundingMcpClient.parsePoints(body, "BTCUSDT", 100));
        body.withArray("points").add(body.withArray("points").get(0).deepCopy());
        assertThrows(ExchangeException.class, () -> FundingMcpClient.parsePoints(body, "BTCUSDT", 1));
    }

    @Test
    void acceptsEmptyStructuredResult() {
        assertEquals(List.of(), parseSamplePoints("[]"));
    }

    @Test
    void successUsesStructuredContentInsteadOfDisplayText() {
        McpSyncClient session = initializedSession();
        when(session.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
                McpSchema.CallToolResult.builder().structuredContent(validBody("BTCUSDT"))
                        .addTextContent("这是展示文本，不是业务 JSON").isError(false).build());
        FundingMcpClient client = new FundingMcpClient(new McpConfig(), () -> session);
        try {
            assertEquals(1, client.fundingRateHistory("BTCUSDT", 0, 86_400_000L, 100).size());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void businessErrorNeedsNoStructuredContentAndKeepsClient() {
        McpSyncClient session = initializedSession();
        when(session.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
                McpSchema.CallToolResult.builder().isError(true)
                        .addTextContent("上游查询失败").addTextContent("交易对不存在").build());
        FundingMcpClient client = new FundingMcpClient(new McpConfig(), () -> session);
        try {
            ExchangeException error = assertThrows(ExchangeException.class,
                    () -> client.fundingRateHistory("BTCUSDT", 0, 86_400_000L, 100));
            assertTrue(error.getMessage().contains("上游查询失败\n交易对不存在"));
            verify(session, never()).closeGracefully();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void callFailureRetainsClientAndNextCallSucceeds() {
        McpSyncClient session = initializedSession();
        RuntimeException failure = new RuntimeException("请求超时", new TimeoutException());
        when(session.callTool(any(McpSchema.CallToolRequest.class)))
                .thenThrow(failure).thenReturn(successfulResult());
        AtomicInteger created = new AtomicInteger();
        FundingMcpClient client = new FundingMcpClient(new McpConfig(), () -> {
            created.incrementAndGet();
            return session;
        });
        NodeTraceCollector.Session trace = new NodeTraceCollector.Session();
        NodeTraceCollector.bind(trace);
        try {
            ExchangeException error = assertThrows(ExchangeException.class,
                    () -> client.fundingRateHistory("BTCUSDT", 0, 86_400_000L, 100));
            assertSame(failure, error.getCause());
            verify(session, never()).closeGracefully();

            List<FundingRatePoint> points = client.fundingRateHistory("BTCUSDT", 0, 86_400_000L, 100);
            assertEquals(List.of(new FundingRatePoint(new BigDecimal("0.0001"), 1_700_006_400_000L)), points);
            assertEquals(1, created.get());
            verify(session, times(1)).initialize();
            verify(session, times(2)).callTool(any(McpSchema.CallToolRequest.class));
            verify(session, never()).closeGracefully();

            List<NodeTraceCollector.Draft> events = trace.freezeAndSnapshot();
            assertEquals(2, events.size());
            assertEquals("请求超时", events.get(0).error());
            assertNull(events.get(0).outputJson());
            assertNull(events.get(1).error());
            assertEquals(points, FundingMcpClient.parsePoints(json(events.get(1).outputJson()), "BTCUSDT", 100));
        } finally {
            NodeTraceCollector.clear();
            client.shutdown();
        }
        verify(session, times(1)).closeGracefully();
    }

    @Test
    void failedCallDoesNotCloseAnotherInFlightCall() throws Exception {
        McpSyncClient session = initializedSession();
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        doAnswer(invocation -> {
            closed.set(true);
            return true;
        }).when(session).closeGracefully();
        when(session.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            if ("ETHUSDT".equals(request.arguments().get("symbol"))) {
                inFlight.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "未释放在途调用");
                assertFalse(closed.get(), "另一个请求失败时不能关闭共享客户端");
                return McpSchema.CallToolResult.builder().structuredContent(validBody("ETHUSDT"))
                        .isError(false).build();
            }
            throw new IllegalStateException("另一个请求失败");
        });
        AtomicInteger created = new AtomicInteger();
        FundingMcpClient client = new FundingMcpClient(new McpConfig(), () -> {
            created.incrementAndGet();
            return session;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> client.fundingRateHistory("ETHUSDT", 0, 86_400_000L, 100));
            assertTrue(inFlight.await(10, TimeUnit.SECONDS), "并发请求未进入 callTool");

            assertThrows(ExchangeException.class,
                    () -> client.fundingRateHistory("BTCUSDT", 0, 86_400_000L, 100));
            verify(session, never()).closeGracefully();
            release.countDown();
            assertEquals(1, pending.get(10, TimeUnit.SECONDS).size());
            assertEquals(1, created.get());
            verify(session, times(1)).initialize();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            client.shutdown();
        }
    }

    private static McpSyncClient initializedSession() {
        McpSyncClient session = mock(McpSyncClient.class);
        McpSchema.InitializeResult init = mock(McpSchema.InitializeResult.class);
        when(init.serverInfo()).thenReturn(new McpSchema.Implementation("test-server", "1.0"));
        when(init.protocolVersion()).thenReturn("2025-03-26");
        when(session.initialize()).thenReturn(init);
        return session;
    }

    private static McpSchema.CallToolResult successfulResult() {
        return McpSchema.CallToolResult.builder()
                .structuredContent(validBody("BTCUSDT"))
                .isError(false)
                .build();
    }

    private static ObjectNode validBody(String symbol) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("exchange", "binance");
        body.put("symbol", symbol);
        body.put("count", 1);
        body.set("points", json("[{\"rate\":\"0.0001\",\"fundingTime\":1700006400000}]"));
        return body;
    }

    private static List<FundingRatePoint> parseSamplePoints(String pointsJson) {
        ObjectNode body = validBody("BTCUSDT");
        JsonNode points = json(pointsJson);
        body.set("points", points);
        body.put("count", points.size());
        return FundingMcpClient.parsePoints(body, "BTCUSDT", 100);
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new AssertionError("测试样本必须是合法 JSON", e);
        }
    }

    @Test
    void handshakeFailureClosesClientAndNextCallRetries() {
        // 握手失败的实例没进共享字段，connect() 必须就地关闭；下一次调用重新建连
        McpSyncClient failing = org.mockito.Mockito.mock(McpSyncClient.class);
        org.mockito.Mockito.when(failing.initialize()).thenThrow(new IllegalStateException("握手被拒"));
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        FundingMcpClient client = new FundingMcpClient(new McpConfig(), () -> {
            created.incrementAndGet();
            return failing;
        });

        assertThrows(ExchangeException.class,
                () -> client.fundingRateHistory("BTCUSDT", 0, 86_400_000L, 100));
        assertThrows(ExchangeException.class,
                () -> client.fundingRateHistory("BTCUSDT", 0, 86_400_000L, 100));

        assertEquals(2, created.get()); // 两次调用两次建连尝试（失败不缓存）
        org.mockito.Mockito.verify(failing, org.mockito.Mockito.times(2)).closeGracefully(); // 每次都就地关闭
    }
}
