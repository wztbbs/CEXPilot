package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.model.OpenInterestInfo;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.TradePoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // 第 8 列为 USDT 成交额；币安无完结状态字段
        assertEquals(new BigDecimal("7410000"), candles.get(0).quoteVolume());
        assertNull(candles.get(0).confirmed());
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
        // 第 8 列 volCcyQuote 为 USDT 成交额，第 9 列 confirm 映射为完结状态
        assertEquals(new BigDecimal("7200"), candles.get(0).quoteVolume());
        assertEquals(Boolean.TRUE, candles.get(0).confirmed());
    }

    @Test
    void okxUnconfirmedCandleKeepsSourceFlag() throws Exception {
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree(
                "[\"1726765200000\", \"60300\", \"60400\", \"60200\", \"60350\", \"10\", \"0.1\", \"6030\", \"0\"]"));
        List<Candle> candles = OkxClient.parseCandles(data);
        assertEquals(Boolean.FALSE, candles.get(0).confirmed());
    }

    @Test
    void okxOiHistoryTakesNewestRowsReversedToAscending() throws Exception {
        // 指定合约接口返回 [ts, oi(张), oiCcy(币), oiUsd]。
        // 张数、币数和名义值使用不同数值，避免误取原接口列。
        ArrayNode data = MAPPER.createArrayNode();
        for (int i = 29; i >= 0; i--) {
            ArrayNode row = MAPPER.createArrayNode();
            row.add(1000 + i);
            row.add("888888");
            row.add("2" + i + ".5");
            row.add("999999");
            data.add(row);
        }
        List<OiPoint> points = OkxClient.parseOiHistory(data);
        // 解析保留全部 30 条并翻转为升序（分页与截断归 OiSource 负责），值取 oiCcy 列
        assertEquals(30, points.size());
        assertEquals(1000, points.get(0).timestamp());
        assertEquals(1029, points.get(29).timestamp());
        assertEquals(new BigDecimal("20.5"), points.get(0).oi());
        assertEquals(new BigDecimal("229.5"), points.get(29).oi());
    }

    @Test
    void okxOiHistoryLimitExceedsData() throws Exception {
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree("[\"2000\", \"9000\", \"31.5\", \"888\"]"));
        data.add(MAPPER.readTree("[\"1000\", \"8000\", \"30.5\", \"999\"]"));
        List<OiPoint> points = OkxClient.parseOiHistory(data);
        assertEquals(2, points.size());
        assertEquals(1000, points.get(0).timestamp());
        assertEquals(2000, points.get(1).timestamp());
    }

    @Test
    void binanceOiHistoryTakesBaseQuantity() throws Exception {
        // 统一使用基础币数量，不取名义价值列。
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree(
                "{\"symbol\":\"BTCUSDT\",\"sumOpenInterest\":\"108898.706\",\"sumOpenInterestValue\":\"8829251567.32\",\"timestamp\":1726761600000}"));
        List<OiPoint> points = BinanceClient.parseOiHistory(data);
        assertEquals(1, points.size());
        assertEquals(1726761600000L, points.get(0).timestamp());
        assertEquals(new BigDecimal("108898.706"), points.get(0).oi());
    }

    @Test
    void binanceDepthMapsToNearestValidLevel() {
        // 币安盘口只接受 5/10/20/50，其余深度须映射到最近合法档（等距取更深一档）
        assertEquals(5, BinanceClient.mapDepth(5));
        assertEquals(5, BinanceClient.mapDepth(7));
        assertEquals(10, BinanceClient.mapDepth(8));
        assertEquals(20, BinanceClient.mapDepth(15));
        assertEquals(50, BinanceClient.mapDepth(50));
    }

    @Test
    void okxTickerConvertsContractsToCoinAndUsdt() throws Exception {
        // OKX ticker 的 vol24h 是张数、volCcy24h 是币数；张数绝不能进 base/quote 成交量
        com.fasterxml.jackson.databind.JsonNode item = MAPPER.readTree(
                "{\"last\":\"2618.68\",\"open24h\":\"2600\",\"vol24h\":\"16115379.9\",\"volCcy24h\":\"1611537.99\",\"ts\":\"1726761600000\"}");
        var ticker = OkxClient.parseTicker(item);
        assertEquals(new BigDecimal("1611537.99"), ticker.baseVolume24h());
        // quote = 币数 × 最新价 = 1611537.99 × 2618.68 ≈ 42.2 亿 USDT，必须带估算标志
        assertEquals(0, new BigDecimal("4220102303.6532").compareTo(ticker.quoteVolume24h()));
        assertEquals(true, ticker.quoteVolumeEstimated());
        assertEquals(1726761600000L, ticker.timestamp());
    }

    @Test
    void okxTickerZeroOpen24hGivesNullChangePct() throws Exception {
        // open24h 为 0 时涨跌幅无定义，必须返回 null 而不是伪造的 0%
        com.fasterxml.jackson.databind.JsonNode item = MAPPER.readTree(
                "{\"last\":\"2618.68\",\"open24h\":\"0\",\"vol24h\":\"0\",\"volCcy24h\":\"0\",\"ts\":\"1726761600000\"}");
        var ticker = OkxClient.parseTicker(item);
        assertEquals(null, ticker.changePct24h());
    }

    @Test
    void binanceAggTradesParseBuyerMakerInverted() throws Exception {
        // m=true（买方是挂单方）→ 主动方为卖方；m=false → 主动买入
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(MAPPER.readTree(
                "{\"a\":\"123\",\"p\":\"60000.5\",\"q\":\"0.25\",\"f\":1,\"l\":3,\"T\":1726761600000,\"m\":true}"));
        arr.add(MAPPER.readTree(
                "{\"a\":\"124\",\"p\":\"60001\",\"q\":\"1.5\",\"f\":4,\"l\":4,\"T\":1726761601000,\"m\":false}"));
        List<TradePoint> trades = BinanceClient.parseAggTrades(arr);
        assertEquals(2, trades.size());
        assertEquals("123", trades.get(0).tradeId());
        assertEquals(1726761600000L, trades.get(0).timestamp());
        assertEquals(new BigDecimal("0.25"), trades.get(0).qty());
        assertFalse(trades.get(0).takerBuy());
        assertTrue(trades.get(1).takerBuy());
    }

    @Test
    void okxHistoryTradesReversedToAscendingAndKeepsContractQty() throws Exception {
        // 倒序返回（最新在前），翻转为升序；sz 保留张数原值，币数换算在 source 做
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree(
                "{\"instId\":\"BTC-USDT-SWAP\",\"tradeId\":\"200\",\"px\":\"60100\",\"sz\":\"3\",\"side\":\"sell\",\"ts\":\"1726761601000\"}"));
        data.add(MAPPER.readTree(
                "{\"instId\":\"BTC-USDT-SWAP\",\"tradeId\":\"199\",\"px\":\"60090\",\"sz\":\"2\",\"side\":\"buy\",\"ts\":\"1726761600000\"}"));
        List<TradePoint> trades = OkxClient.parseHistoryTrades(data);
        assertEquals(2, trades.size());
        assertEquals("199", trades.get(0).tradeId());
        assertEquals(1726761600000L, trades.get(0).timestamp());
        assertTrue(trades.get(0).takerBuy());
        assertFalse(trades.get(1).takerBuy());
        assertEquals(new BigDecimal("2"), trades.get(0).qty());
    }

    @Test
    void okxMarkPriceCandlesSixColumnsWithConfirm() throws Exception {
        // 标记/指数价格 K 线：6 列 [ts, o, h, l, c, confirm]，无成交量
        ArrayNode data = MAPPER.createArrayNode();
        data.add(MAPPER.readTree("[\"1726765200000\",\"60300\",\"60400\",\"60200\",\"60350\",\"0\"]"));
        data.add(MAPPER.readTree("[\"1726761600000\",\"60000\",\"60500\",\"59800\",\"60300\",\"1\"]"));
        List<Candle> candles = OkxClient.parseMarkPriceCandles(data);
        assertEquals(2, candles.size());
        assertEquals(1726761600000L, candles.get(0).openTime());
        assertEquals(1726765200000L, candles.get(1).openTime());
        assertNull(candles.get(0).volume());
        assertNull(candles.get(0).quoteVolume());
        assertEquals(Boolean.TRUE, candles.get(0).confirmed());
        assertEquals(Boolean.FALSE, candles.get(1).confirmed());
    }
}
