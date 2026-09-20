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
 * OKX v5 公共行情，统一使用 USDT 永续合约（instId 形如 BTC-USDT-SWAP）。
 * OKX 的 candles / trades 接口返回是倒序（最新在前），这里统一翻转为时间升序。
 */
@Component
public class OkxClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(OkxClient.class);
    private static final String NAME = "okx";

    private final RestClient rest;
    private final String baseUrl;

    public OkxClient(ExchangeConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        String baseUrl = config.getOkx().getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public List<Candle> candles(String instId, String bar, int limit) {
        JsonNode data = get("/api/v5/market/candles?instId={i}&bar={b}&limit={l}", instId, bar, limit);
        return parseCandles(data);
    }

    public Ticker ticker(String instId) {
        JsonNode data = get("/api/v5/market/ticker?instId={i}", instId);
        return parseTicker(first(data, "ticker"));
    }

    /**
     * OKX ticker 没有 USDT 成交额字段：vol24h 是张数、volCcy24h 是基础币数。
     * 统一为币安同口径：baseVolume24h = 币数（volCcy24h），
     * quoteVolume24h = 币数 × 最新价（USDT 估算值，24h 内价格变动会带来小误差）。
     */
    static Ticker parseTicker(JsonNode item) {
        BigDecimal last = decimal(item, "last");
        BigDecimal open24h = decimal(item, "open24h");
        BigDecimal changePct = BigDecimal.ZERO;
        if (open24h.signum() > 0) {
            changePct = last.subtract(open24h)
                    .divide(open24h, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }
        BigDecimal volumeBase = decimal(item, "volCcy24h");
        return new Ticker(last, changePct, volumeBase, volumeBase.multiply(last));
    }

    /** 资金费率 + 下次结算时间。 */
    public MarkPrice fundingRate(String instId) {
        JsonNode data = get("/api/v5/public/funding-rate?instId={i}", instId);
        JsonNode item = first(data, "funding-rate");
        return new MarkPrice(null, null, decimal(item, "fundingRate"),
                item.path("nextFundingTime").asLong(0));
    }

    public List<BigDecimal> fundingRateHistory(String instId, int limit) {
        JsonNode data = get("/api/v5/public/funding-rate-history?instId={i}&limit={l}", instId, limit);
        List<BigDecimal> rates = new ArrayList<>();
        for (JsonNode item : data) {
            // 接口倒序返回，翻转为升序
            rates.add(0, decimal(item, "fundingRate"));
        }
        return rates;
    }

    /** 当前持仓量（oiUsd，USD 名义值），与 rubik 历史序列同单位。 */
    public BigDecimal openInterest(String instId) {
        JsonNode data = get("/api/v5/public/open-interest?instType=SWAP&instId={i}", instId);
        JsonNode item = first(data, "open-interest");
        // 缺 oiUsd 时宁可报错也不回退到其他单位，避免与 USD 历史序列混口径
        return decimal(item, "oiUsd");
    }

    /**
     * 持仓量历史（rubik 统计接口，按币种汇总全市场合约，单位为 USD）。
     * 返回行格式 [ts, oiUsd, volUsd]，最新在前，且数据跨度（约 30 天）远大于 limit：
     * 必须取头部 limit 条（最新），再翻转为时间升序。
     */
    public List<OiPoint> openInterestHistory(String ccy, String period, int limit) {
        JsonNode data = get("/api/v5/rubik/stat/contracts/open-interest-volume?ccy={c}&period={p}",
                ccy, period);
        return parseOiHistory(data, limit);
    }

    static List<OiPoint> parseOiHistory(JsonNode data, int limit) {
        List<OiPoint> points = new ArrayList<>();
        int size = Math.min(limit, data.size());
        for (int i = size - 1; i >= 0; i--) {
            JsonNode row = data.get(i);
            points.add(new OiPoint(row.get(0).asLong(), new BigDecimal(row.get(1).asText())));
        }
        return points;
    }

    public OrderBook orderBook(String instId, int depth) {
        JsonNode data = get("/api/v5/market/books?instId={i}&sz={d}", instId, depth);
        JsonNode item = first(data, "books");
        return new OrderBook(parseLevels(item.path("bids")), parseLevels(item.path("asks")));
    }

    public List<Trade> trades(String instId, int limit) {
        JsonNode data = get("/api/v5/market/trades?instId={i}&limit={l}", instId, limit);
        List<Trade> trades = new ArrayList<>();
        for (int i = data.size() - 1; i >= 0; i--) {
            JsonNode item = data.get(i);
            trades.add(new Trade(
                    item.path("ts").asLong(),
                    decimal(item, "px"),
                    decimal(item, "sz"),
                    "buy".equalsIgnoreCase(item.path("side").asText())));
        }
        return trades;
    }

    public MarkPrice markPrice(String instId, String indexInstId) {
        JsonNode markData = get("/api/v5/public/mark-price?instType=SWAP&instId={i}", instId);
        JsonNode markItem = first(markData, "mark-price");
        JsonNode indexData = get("/api/v5/market/index-tickers?instId={i}", indexInstId);
        JsonNode indexItem = first(indexData, "index-tickers");
        return new MarkPrice(decimal(markItem, "markPx"), decimal(indexItem, "idxPx"), null, 0);
    }

    private JsonNode get(String uri, Object... vars) {
        long start = System.currentTimeMillis();
        log.info("okx 请求 GET {}{} 参数={}", baseUrl, uri, vars);
        try {
            String raw = rest.get().uri(uri, vars).retrieve().body(String.class);
            log.info("okx 响应 200 {}ms {} body={}",
                    System.currentTimeMillis() - start, uri, abbreviate(raw));
            JsonNode root = MAPPER.readTree(raw);
            String code = root.path("code").asText("");
            if (!"0".equals(code)) {
                // OKX 业务错误码：HTTP 200 但 code != "0"
                log.warn("okx 接口返回错误 {} code={} msg={}", uri, code, root.path("msg").asText(""));
                throw new ExchangeException(NAME,
                        "接口返回错误 code=" + code + " msg=" + root.path("msg").asText(""));
            }
            return root.path("data");
        } catch (ExchangeException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // HTTP 错误状态：状态码 + 响应 body 必须留下来
            log.warn("okx 请求失败 {} {}ms 状态={} body={}",
                    uri, System.currentTimeMillis() - start,
                    e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            throw new ExchangeException(NAME,
                    "请求失败 " + uri + ": HTTP " + e.getStatusCode() + " " + e.getStatusText(), e);
        } catch (Exception e) {
            log.warn("okx 请求异常 {} {}ms: {}", uri, System.currentTimeMillis() - start, e.getMessage());
            throw new ExchangeException(NAME, "请求失败 " + uri + ": " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "...";
    }

    private static JsonNode first(JsonNode data, String api) {
        if (!data.isArray() || data.isEmpty()) {
            throw new ExchangeException(NAME, api + " 接口返回空数据");
        }
        return data.get(0);
    }

    static List<Candle> parseCandles(JsonNode data) {
        List<Candle> candles = new ArrayList<>();
        for (int i = data.size() - 1; i >= 0; i--) {
            JsonNode row = data.get(i);
            candles.add(new Candle(
                    row.get(0).asLong(),
                    new BigDecimal(row.get(1).asText()),
                    new BigDecimal(row.get(2).asText()),
                    new BigDecimal(row.get(3).asText()),
                    new BigDecimal(row.get(4).asText()),
                    // OKX candles 第 5 列是张数，第 6 列才是基础币数（与币安 volume 口径一致）
                    new BigDecimal(row.get(6).asText())));
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
