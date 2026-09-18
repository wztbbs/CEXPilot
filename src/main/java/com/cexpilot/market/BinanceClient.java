package com.cexpilot.market;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.MarkPrice;
import com.cexpilot.market.model.OpenInterestInfo.OiPoint;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.market.model.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Binance USDⓈ-M 永续合约公共行情。公共行情接口不需要 API Key（Key 留给后续私有接口）。
 * 解析方法与 HTTP 分离，方便用录制的响应样本做单元测试。
 */
@Component
public class BinanceClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NAME = "binance";

    private final RestClient rest;

    public BinanceClient(ExchangeConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        String baseUrl = config.getBinance().getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public List<Candle> klines(String symbol, String interval, int limit) {
        return parseCandles(get("/fapi/v1/klines?symbol={s}&interval={i}&limit={l}",
                symbol, interval, limit));
    }

    public Ticker ticker24h(String symbol) {
        JsonNode node = get("/fapi/v1/ticker/24hr?symbol={s}", symbol);
        return new Ticker(
                decimal(node, "lastPrice"),
                decimal(node, "priceChangePercent"),
                decimal(node, "volume"),
                decimal(node, "quoteVolume"));
    }

    /** premiumIndex 同时给出标记价格、指数价格、当期资金费率。 */
    public MarkPrice premiumIndex(String symbol) {
        JsonNode node = get("/fapi/v1/premiumIndex?symbol={s}", symbol);
        return new MarkPrice(
                decimal(node, "markPrice"),
                decimal(node, "indexPrice"),
                decimal(node, "lastFundingRate"),
                node.path("nextFundingTime").asLong(0));
    }

    public List<BigDecimal> fundingRateHistory(String symbol, int limit) {
        JsonNode node = get("/fapi/v1/fundingRate?symbol={s}&limit={l}", symbol, limit);
        List<BigDecimal> rates = new ArrayList<>();
        for (JsonNode item : node) {
            rates.add(decimal(item, "fundingRate"));
        }
        return rates;
    }

    public BigDecimal openInterest(String symbol) {
        JsonNode node = get("/fapi/v1/openInterest?symbol={s}", symbol);
        return decimal(node, "openInterest");
    }

    public List<OiPoint> openInterestHistory(String symbol, String period, int limit) {
        JsonNode node = get("/futures/data/openInterestHist?symbol={s}&period={p}&limit={l}",
                symbol, period, limit);
        List<OiPoint> points = new ArrayList<>();
        for (JsonNode item : node) {
            points.add(new OiPoint(item.path("timestamp").asLong(),
                    decimal(item, "sumOpenInterest")));
        }
        return points;
    }

    public OrderBook depth(String symbol, int limit) {
        JsonNode node = get("/fapi/v1/depth?symbol={s}&limit={l}", symbol, limit);
        return new OrderBook(parseLevels(node.path("bids")), parseLevels(node.path("asks")));
    }

    public List<Trade> trades(String symbol, int limit) {
        JsonNode node = get("/fapi/v1/trades?symbol={s}&limit={l}", symbol, limit);
        List<Trade> trades = new ArrayList<>();
        for (JsonNode item : node) {
            // isBuyerMaker=true 表示买方是挂单方，即主动方是卖方
            trades.add(new Trade(
                    item.path("time").asLong(),
                    decimal(item, "price"),
                    decimal(item, "qty"),
                    !item.path("isBuyerMaker").asBoolean(true)));
        }
        return trades;
    }

    private JsonNode get(String uri, Object... vars) {
        try {
            String raw = rest.get().uri(uri, vars).retrieve().body(String.class);
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ExchangeException(NAME, "请求失败 " + uri + ": " + e.getMessage(), e);
        }
    }

    static List<Candle> parseCandles(JsonNode node) {
        List<Candle> candles = new ArrayList<>();
        for (JsonNode row : node) {
            candles.add(new Candle(
                    row.get(0).asLong(),
                    new BigDecimal(row.get(1).asText()),
                    new BigDecimal(row.get(2).asText()),
                    new BigDecimal(row.get(3).asText()),
                    new BigDecimal(row.get(4).asText()),
                    new BigDecimal(row.get(5).asText())));
        }
        return candles;
    }

    static List<OrderBook.Level> parseLevels(JsonNode node) {
        List<OrderBook.Level> levels = new ArrayList<>();
        for (JsonNode row : node) {
            levels.add(new OrderBook.Level(
                    new BigDecimal(row.get(0).asText()),
                    new BigDecimal(row.get(1).asText())));
        }
        return levels;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String text = node.path(field).asText(null);
        if (text == null || text.isBlank()) {
            throw new ExchangeException(NAME, "响应缺少字段 " + field);
        }
        return new BigDecimal(text);
    }
}
