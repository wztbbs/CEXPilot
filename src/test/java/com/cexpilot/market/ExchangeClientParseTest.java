package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.OpenInterestInfo;
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
        // OKX 倒序返回：最新在前；列序 [ts, o, h, l, c, vol(张), volCcy(币), volCcyQuote, confirm]
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree(
                "[\"1726765200000\", \"60300\", \"60400\", \"60200\", \"60350\", \"10\", \"0.1\", \"6030\", \"1\"]"));
        data.add(MAPPER.readTree(
                "[\"1726761600000\", \"60000\", \"60500\", \"59800\", \"60300\", \"12\", \"0.12\", \"7200\", \"1\"]"));
        List<Candle> candles = OkxClient.parseCandles(data);
        assertEquals(2, candles.size());
        assertEquals(1726761600000L, candles.get(0).openTime());
        assertEquals(1726765200000L, candles.get(1).openTime());
        assertEquals(new BigDecimal("60300"), candles.get(0).close());
        // volume 取第 6 列（币数），不取第 5 列（张数）
        assertEquals(new BigDecimal("0.12"), candles.get(0).volume());
        assertEquals(new BigDecimal("0.1"), candles.get(1).volume());
    }

    @Test
    void okxOiHistoryTakesNewestRowsReversedToAscending() throws Exception {
        // rubik 返回行 [ts, oiUsd, volUsd]，最新在前，跨度远超 limit。
        // 构造 30 条倒序数据：volUsd 用扎眼的常量，取错列会立刻暴露。
        ArrayNode data = MAPPER.createArrayNode();
        for (int i = 29; i >= 0; i--) {
            ArrayNode row = MAPPER.createArrayNode();
            row.add(1000 + i);
            row.add("2" + i + ".5");
            row.add("999999");
            data.add(row);
        }
        List<OpenInterestInfo.OiPoint> points = OkxClient.parseOiHistory(data, 24);
        assertEquals(24, points.size());
        // 应取最新 24 条（ts 1006..1029）并翻转为升序，值取 oiUsd 列
        assertEquals(1006, points.get(0).timestamp());
        assertEquals(1029, points.get(23).timestamp());
        assertEquals(new BigDecimal("26.5"), points.get(0).oi());
        assertEquals(new BigDecimal("229.5"), points.get(23).oi());
    }

    @Test
    void okxOiHistoryLimitExceedsData() throws Exception {
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree("[\"2000\", \"31.5\", \"888\"]"));
        data.add(MAPPER.readTree("[\"1000\", \"30.5\", \"999\"]"));
        List<OpenInterestInfo.OiPoint> points = OkxClient.parseOiHistory(data, 24);
        assertEquals(2, points.size());
        assertEquals(1000, points.get(0).timestamp());
        assertEquals(2000, points.get(1).timestamp());
    }

    @Test
    void binanceOiHistoryTakesUsdNotional() throws Exception {
        // openInterestHist 行同时带币数（sumOpenInterest）和 USD 名义值（sumOpenInterestValue），
        // 统一口径要求取 USD 列
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree(
                "{\"symbol\":\"BTCUSDT\",\"sumOpenInterest\":\"108898.706\",\"sumOpenInterestValue\":\"8829251567.32\",\"timestamp\":1726761600000}"));
        List<OpenInterestInfo.OiPoint> points = BinanceClient.parseOiHistory(data);
        assertEquals(1, points.size());
        assertEquals(1726761600000L, points.get(0).timestamp());
        assertEquals(new BigDecimal("8829251567.32"), points.get(0).oi());
    }

    @Test
    void okxTickerConvertsContractsToCoinAndUsdt() throws Exception {
        // OKX ticker 的 vol24h 是张数、volCcy24h 是币数；张数绝不能进 base/quote 成交量
        com.fasterxml.jackson.databind.JsonNode item = MAPPER.readTree(
                "{\"last\":\"2618.68\",\"open24h\":\"2600\",\"vol24h\":\"16115379.9\",\"volCcy24h\":\"1611537.99\"}");
        var ticker = OkxClient.parseTicker(item);
        assertEquals(new BigDecimal("1611537.99"), ticker.baseVolume24h());
        // quote = 币数 × 最新价 = 1611537.99 × 2618.68 ≈ 42.2 亿 USDT
        assertEquals(0, new BigDecimal("4220102303.6532").compareTo(ticker.quoteVolume24h()));
    }
}
