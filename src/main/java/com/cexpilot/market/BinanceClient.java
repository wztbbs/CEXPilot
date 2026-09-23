package com.cexpilot.market;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.FundingInfo.RatePoint;
import com.cexpilot.market.model.MarkPrice;
import com.cexpilot.market.model.OpenInterestInfo.OiPoint;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.market.model.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
    private static final Logger log = LoggerFactory.getLogger(BinanceClient.class);
    private static final String NAME = "binance";

    private final RestClient rest;
    private final String baseUrl;

    public BinanceClient(ExchangeConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        String baseUrl = config.getBinance().getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
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
                decimal(node, "quoteVolume"),
                false,
                node.path("closeTime").asLong(0));
    }

    /** premiumIndex 同时给出标记价格、指数价格、最近一期已结算资金费率。 */
    public MarkPrice premiumIndex(String symbol) {
        JsonNode node = get("/fapi/v1/premiumIndex?symbol={s}", symbol);
        long time = node.path("time").asLong(0);
        return new MarkPrice(
                decimal(node, "markPrice"),
                decimal(node, "indexPrice"),
                decimal(node, "lastFundingRate"),
                node.path("nextFundingTime").asLong(0),
                time, time);
    }

    /** 历史资金费率：已结算费率，保留各期结算时间。 */
    public List<RatePoint> fundingRateHistory(String symbol, int limit) {
        JsonNode node = get("/fapi/v1/fundingRate?symbol={s}&limit={l}", symbol, limit);
        List<RatePoint> rates = new ArrayList<>();
        for (JsonNode item : node) {
            rates.add(new RatePoint(decimal(item, "fundingRate"),
                    item.path("fundingTime").asLong(0)));
        }
        return rates;
    }

    public BigDecimal openInterest(String symbol) {
        JsonNode node = get("/fapi/v1/openInterest?symbol={s}", symbol);
        return decimal(node, "openInterest");
    }

    /** 指定 USDT 永续合约的持仓数量历史，sumOpenInterest 单位为基础币。 */
    public List<OiPoint> openInterestHistory(String symbol, String period, int limit) {
        JsonNode node = get("/futures/data/openInterestHist?symbol={s}&period={p}&limit={l}",
                symbol, period, limit);
        return parseOiHistory(node);
    }

    static List<OiPoint> parseOiHistory(JsonNode data) {
        List<OiPoint> points = new ArrayList<>();
        for (JsonNode item : data) {
            points.add(new OiPoint(item.path("timestamp").asLong(),
                    decimal(item, "sumOpenInterest")));
        }
        points.sort(java.util.Comparator.comparingLong(OiPoint::timestamp));
        return points;
    }

    /** 币安盘口只接受 5/10/20/50 档，其余深度会被上游 400 拒绝；映射到最近的合法档位（等距取更深一档）。 */
    public static int mapDepth(int depth) {
        int[] validDepths = {5, 10, 20, 50};
        int best = validDepths[0];
        for (int valid : validDepths) {
            if (Math.abs(valid - depth) <= Math.abs(best - depth)) {
                best = valid;
            }
        }
        return best;
    }

    public OrderBook depth(String symbol, int limit) {
        JsonNode node = get("/fapi/v1/depth?symbol={s}&limit={l}", symbol, mapDepth(limit));
        return new OrderBook(parseLevels(node.path("bids")), parseLevels(node.path("asks")),
                "base", node.path("T").asLong(node.path("E").asLong(0)));
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
                    !item.path("isBuyerMaker").asBoolean(true),
                    "base"));
        }
        return trades;
    }

    private JsonNode get(String uri, Object... vars) {
        long start = System.currentTimeMillis();
        log.info("binance 请求 GET {}{} 参数={}", baseUrl, uri, vars);
        try {
            String raw = rest.get().uri(uri, vars).retrieve().body(String.class);
            log.info("binance 响应 200 {}ms {} body={}",
                    System.currentTimeMillis() - start, uri, abbreviate(raw));
            return MAPPER.readTree(raw);
        } catch (RestClientResponseException e) {
            // HTTP 错误状态（如 451 地域限制）：状态码 + 响应 body 必须留下来
            log.warn("binance 请求失败 {} {}ms 状态={} body={}",
                    uri, System.currentTimeMillis() - start,
                    e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            throw new ExchangeException(NAME,
                    "请求失败 " + uri + ": HTTP " + e.getStatusCode() + " " + e.getStatusText(), e);
        } catch (Exception e) {
            log.warn("binance 请求异常 {} {}ms: {}", uri, System.currentTimeMillis() - start, e.getMessage());
            throw new ExchangeException(NAME, "请求失败 " + uri + ": " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "...";
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
