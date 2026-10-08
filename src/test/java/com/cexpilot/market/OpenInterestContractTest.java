package com.cexpilot.market;

import com.cexpilot.config.ExchangeConfig;
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
    void snapshotUsesRealSnapshotEndpointsWithUnitAndDataTime() throws Exception {
        List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            String body = switch (request.getRequestURI().getPath()) {
                case "/fapi/v1/openInterest" ->
                        "{\"openInterest\":\"104091.102\",\"symbol\":\"BTCUSDT\",\"time\":1726765200000}";
                case "/api/v5/public/open-interest" -> """
                        {"code":"0","data":[{"instType":"SWAP","instId":"BTC-USDT-SWAP","oi":"2962556","oiCcy":"29645.9406","oiUsd":"2496890807","ts":"1726765200000"}]}
                        """;
                default -> "{\"code\":\"1\",\"msg\":\"unexpected endpoint\"}";
            };
            requests.add(request.getRequestURI().toString());
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
            for (Exchange exchange : Exchange.values()) {
                var snapshot = service.oiSnapshot(exchange, "BTC");
                assertEquals("BTC", snapshot.unit());
                assertEquals(new BigDecimal(exchange == Exchange.OKX ? "29645.9406" : "104091.102"), snapshot.oi());
                assertEquals(Times.readable(1726765200000L), Times.readable(snapshot.dataTime()));
                assertTrue(snapshot.snapshotTime() >= snapshot.dataTime(), "快照采集时间不早于数据时间");
            }
            assertEquals(List.of(
                    "/fapi/v1/openInterest?symbol=BTCUSDT",
                    "/api/v5/public/open-interest?instType=SWAP&instId=BTC-USDT-SWAP"), requests);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oldAggregateShapeAndMissingCoinQuantityAreRejected() throws Exception {
        for (String row : List.of("[\"1000\",\"30\",\"999\"]", "[\"1000\",\"30\",null,\"999\"]")) {
            assertThrows(RuntimeException.class, () -> OkxClient.parseOiHistory(MAPPER.readTree("[" + row + "]")));
        }
        assertThrows(RuntimeException.class, () -> BinanceClient.parseOiHistory(
                MAPPER.readTree("[{\"timestamp\":1000,\"sumOpenInterestValue\":\"999\"}]")));
    }

    @Test
    void historyParsedAscendingWithCoinQuantity() throws Exception {
        var points = OkxClient.parseOiHistory(MAPPER.readTree("""
                [["1000","10","0.1","5000"],["2000","20","0.2","10000"],["3000","30","0.3","15000"]]
                """));
        // 解析保留全部行并按时间升序；分页与截断归 OiSource 负责
        assertEquals(List.of(1000L, 2000L, 3000L), points.stream().map(p -> p.timestamp()).toList());
        assertEquals(new BigDecimal("0.3"), points.get(2).oi());
    }
}
