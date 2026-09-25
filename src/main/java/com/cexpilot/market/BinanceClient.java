package com.cexpilot.market;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.model.MarkPrice;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.model.OpenInterestInfo;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.market.model.Trade;
import com.cexpilot.market.model.TradePoint;
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
        factory.setReadTimeout(Duration.ofSeconds(20));
        String baseUrl = config.getBinance().getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 按开区间查询 K 线：[startTimeMs, endTimeMs)（endTime 传区间终点-1ms，不含终点），升序返回。
     * 单页上限 1500 根；跨页拉取由调用方（KlineSource）负责。
     */
    public List<Candle> klines(String symbol, String interval, long startTimeMs, long endTimeMs, int limit) {
        return parseCandles(get("/fapi/v1/klines?symbol={s}&interval={i}&startTime={st}&endTime={et}&limit={l}",
                symbol, interval, startTimeMs, endTimeMs, limit));
    }

    /** 标记价格 K 线：与 klines 同构（volume 列为 "0"），升序返回。 */
    public List<Candle> markPriceKlines(String symbol, String interval,
                                        long startTimeMs, long endTimeMs, int limit) {
        return parseCandles(get("/fapi/v1/markPriceKlines?symbol={s}&interval={i}&startTime={st}&endTime={et}&limit={l}",
                symbol, interval, startTimeMs, endTimeMs, limit));
    }

    /** 指数价格 K 线：注意参数是 pair（不是 symbol），升序返回。 */
    public List<Candle> indexPriceKlines(String pair, String interval,
                                         long startTimeMs, long endTimeMs, int limit) {
        return parseCandles(get("/fapi/v1/indexPriceKlines?pair={p}&interval={i}&startTime={st}&endTime={et}&limit={l}",
                pair, interval, startTimeMs, endTimeMs, limit));
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

    /**
     * 区间历史资金费率：已结算费率，保留各期结算时间，升序返回。
     * 单页上限 1000 条；跨页拉取由调用方（FundingRateSource）负责。
     */
    public List<FundingRatePoint> fundingRateHistory(String symbol, long startTimeMs, long endTimeMs, int limit) {
        JsonNode node = get("/fapi/v1/fundingRate?symbol={s}&startTime={st}&endTime={et}&limit={l}",
                symbol, startTimeMs, endTimeMs, limit);
        List<FundingRatePoint> rates = new ArrayList<>();
        for (JsonNode item : node) {
            rates.add(new FundingRatePoint(decimal(item, "fundingRate"),
                    item.path("fundingTime").asLong(0)));
        }
        return rates;
    }

    /** 当前持仓量快照：openInterest 单位为基础币，time 为数据时间。 */
    public OpenInterestInfo openInterestSnapshot(String symbol) {
        JsonNode node = get("/fapi/v1/openInterest?symbol={s}", symbol);
        return new OpenInterestInfo(decimal(node, "openInterest"), null,
                node.path("time").asLong(0), System.currentTimeMillis());
    }

    /**
     * 区间持仓量历史：sumOpenInterest 单位为基础币，升序返回。单页上限 500 条；
     * 只保留最近约 30 天（startTime 超范围时上游报错）；跨页拉取由调用方（OiSource）负责。
     */
    public List<OiPoint> openInterestHistory(String symbol, String period,
                                             long startTimeMs, long endTimeMs, int limit) {
        JsonNode node = get("/futures/data/openInterestHist?symbol={s}&period={p}&startTime={st}&endTime={et}&limit={l}",
                symbol, period, startTimeMs, endTimeMs, limit);
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

    /**
     * 区间聚合成交（aggTrades）：升序返回窗口内最早 N 条，单页上限 1000。
     * 一笔聚合成交可能含多笔原始成交（f~l 为原始成交 ID 区间）；q 为基础币数量；
     * m=true 表示买方是挂单方，即主动方是卖方。跨页拉取由调用方（TradeSource）负责。
     */
    public List<TradePoint> aggTrades(String symbol, long startTimeMs, long endTimeMs, int limit) {
        JsonNode node = get("/fapi/v1/aggTrades?symbol={s}&startTime={st}&endTime={et}&limit={l}",
                symbol, startTimeMs, endTimeMs, limit);
        return parseAggTrades(node);
    }

    /**
     * 按聚合成交 ID 续页（fromId 含等值，升序返回），与 {@link #aggTrades} 同口径。
     * 同一毫秒可能有多笔聚合成交，按时间戳翻页会漏同毫秒数据，跨页必须用 fromId。
     */
    public List<TradePoint> aggTradesFromId(String symbol, long fromId, int limit) {
        JsonNode node = get("/fapi/v1/aggTrades?symbol={s}&fromId={f}&limit={l}", symbol, fromId, limit);
        return parseAggTrades(node);
    }

    static List<TradePoint> parseAggTrades(JsonNode node) {
        List<TradePoint> trades = new ArrayList<>();
        for (JsonNode item : node) {
            trades.add(new TradePoint(
                    item.path("a").asText(),
                    item.path("T").asLong(),
                    decimal(item, "p"),
                    decimal(item, "q"),
                    !item.path("m").asBoolean(true)));
        }
        return trades;
    }

    /** 分页查询会串行拉多页大响应体，网络/代理偶发中断属常态；I/O 类失败重试，HTTP 状态错误不重试。 */
    private static final int MAX_ATTEMPTS = 3;

    private JsonNode get(String uri, Object... vars) {
        for (int attempt = 1; ; attempt++) {
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
                log.warn("binance 请求异常 {} {}ms 第{}次: {}", uri, System.currentTimeMillis() - start,
                        attempt, e.getMessage());
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ExchangeException(NAME, "请求失败 " + uri + ": " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ExchangeException(NAME, "请求失败 " + uri + ": 重试等待被中断", ie);
                }
            }
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
                    new BigDecimal(row.get(5).asText()),
                    // 第 8 列为 USDT 成交额
                    new BigDecimal(row.get(7).asText()),
                    null));
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
