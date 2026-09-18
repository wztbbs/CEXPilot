package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.OrderBook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用符合公开 API 文档的样本报文验证两个客户端的解析逻辑。
 */
class ExchangeClientParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void binanceCandlesParse() throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(MAPPER.readTree(
                "[1726761600000, \"60000.00\", \"60500.00\", \"59800.00\", \"60300.50\", \"123.456\", 1726765199999, \"7410000\", 1000, \"60\", \"3600000\", \"0\"]"));
        List<Candle> candles = BinanceClient.parseCandles(arr);
        assertEquals(1, candles.size());
        assertEquals(1726761600000L, candles.get(0).openTime());
        assertEquals(new BigDecimal("60300.50"), candles.get(0).close());
        assertEquals(new BigDecimal("123.456"), candles.get(0).volume());
    }

    @Test
    void binanceLevelsParse() throws Exception {
        ArrayNode bids = MAPPER.createArrayNode();
        bids.add(MAPPER.readTree("[\"60000.00\", \"1.5\"]"));
        bids.add(MAPPER.readTree("[\"59999.00\", \"2.5\"]"));
        List<OrderBook.Level> levels = BinanceClient.parseLevels(bids);
        assertEquals(2, levels.size());
        assertEquals(new BigDecimal("60000.00"), levels.get(0).price());
        assertEquals(new BigDecimal("2.5"), levels.get(1).qty());
    }

    @Test
    void okxCandlesReversedToAscending() throws Exception {
        // OKX 倒序返回：最新在前
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree(
                "[\"1726765200000\", \"60300\", \"60400\", \"60200\", \"60350\", \"10\", \"603000\", \"603000\", \"1\"]"));
        data.add(MAPPER.readTree(
                "[\"1726761600000\", \"60000\", \"60500\", \"59800\", \"60300\", \"12\", \"720000\", \"720000\", \"1\"]"));
        List<Candle> candles = OkxClient.parseCandles(data);
        assertEquals(2, candles.size());
        assertEquals(1726761600000L, candles.get(0).openTime());
        assertEquals(1726765200000L, candles.get(1).openTime());
        assertEquals(new BigDecimal("60300"), candles.get(0).close());
    }
}
