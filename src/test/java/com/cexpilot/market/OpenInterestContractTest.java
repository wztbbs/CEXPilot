package com.cexpilot.market;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.market.tool.GetOpenInterestTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Client → Service → Tool 的本机 HTTP 契约测试，不调用交易所。 */
class OpenInterestContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void bothExchangesQuerySinglePerpetualAndReturnCoinQuantity() throws Exception {
        check(false);
    }

    @Test
    void emptyHistoryNeverFallsBackToOtherScopeOrEstimatedValue() throws Exception {
        check(true);
    }

    private void check(boolean empty) throws Exception {
        List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            requests.add(request.getRequestURI().toString());
            String body;
            if (request.getRequestURI().getPath().equals("/api/v5/rubik/stat/contracts/open-interest-history")) {
                body = empty ? "{\"code\":\"0\",\"data\":[]}" : """
                        {"code":"0","data":[
                          ["1726765200000","9999","0.000123456789","987654321"],
                          ["1726761600000","8888","0.000100000001","123456789"]]}
                        """;
            } else if (request.getRequestURI().getPath().equals("/futures/data/openInterestHist")) {
                body = empty ? "[]" : """
                        [{"timestamp":1726761600000,"sumOpenInterest":"0.000100000001","sumOpenInterestValue":"123456789"},
                         {"timestamp":1726765200000,"sumOpenInterest":"0.000123456789","sumOpenInterestValue":"987654321"}]
                        """;
            } else {
                body = "{\"code\":\"1\",\"msg\":\"unexpected endpoint\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.sendResponseHeaders(200, bytes.length);
            try (var output = request.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            ExchangeConfig config = new ExchangeConfig();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            config.getBinance().setBaseUrl(url);
            config.getOkx().setBaseUrl(url);
            MarketDataService service = new MarketDataService(new BinanceClient(config), new OkxClient(config));
            GetOpenInterestTool tool = new GetOpenInterestTool(service);
            for (String exchange : List.of("binance", "okx")) {
                var result = tool.execute(MAPPER.createObjectNode().put("symbol", "BTC").put("exchange", exchange), null);
                assertTrue(result.ok(), result.error());
                var facts = result.data();
                assertEquals("BTC", facts.path("unit").asText());
                assertEquals("该永续合约（1 小时粒度）", facts.path("series_scope").asText());
                assertEquals(exchange.equals("okx") ? "BTC-USDT-SWAP" : "BTCUSDT", facts.path("instrument").asText());
                assertEquals("unverified", facts.path("window_coverage").asText());
                assertFalse(facts.has("oi_change_24h_pct"));
                if (empty) {
                    assertFalse(facts.has("open_interest"));
                    assertFalse(facts.has("oi_change_pct"));
                    assertTrue(facts.path("data_status").asText().contains("数据不足"));
                } else {
                    assertEquals(new BigDecimal("0.000123456789"), facts.path("open_interest").decimalValue());
                    assertEquals(new BigDecimal("0.000100000001"), facts.path("open_interest_series").get(0).get(1).decimalValue());
                    assertEquals(Times.readable(1726765200000L), facts.path("as_of_utc8").asText());
                    assertTrue(facts.has("oi_change_pct"));
                }
            }
            assertEquals(List.of(
                    "/futures/data/openInterestHist?symbol=BTCUSDT&period=1h&limit=24",
                    "/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP&period=1H&limit=24"), requests);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oldAggregateShapeAndMissingCoinQuantityAreRejected() throws Exception {
        for (String row : List.of("[\"1000\",\"30\",\"999\"]", "[\"1000\",\"30\",null,\"999\"]")) {
            assertThrows(RuntimeException.class, () -> OkxClient.parseOiHistory(MAPPER.readTree("[" + row + "]"), 24));
        }
        assertThrows(RuntimeException.class, () -> BinanceClient.parseOiHistory(
                MAPPER.readTree("[{\"timestamp\":1000,\"sumOpenInterestValue\":\"999\"}]")));
    }

    @Test
    void ascendingResponseStillSelectsNewestSamples() throws Exception {
        var points = OkxClient.parseOiHistory(MAPPER.readTree("""
                [["1000","10","0.1","5000"],["2000","20","0.2","10000"],["3000","30","0.3","15000"]]
                """), 2);
        assertEquals(List.of(2000L, 3000L), points.stream().map(p -> p.timestamp()).toList());
        assertEquals(new BigDecimal("0.3"), points.get(1).oi());
    }
}
