package com.cexpilot.market;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.FundingInfo.RatePoint;
import com.cexpilot.market.model.FundingSnapshot;
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
     * quoteVolume24h = 币数 × 最新价（USDT 估算值，24h 内价格变动会带来误差，
     * 由 quoteVolumeEstimated 显式标注）。open24h 为 0 时涨跌幅无法定义，返回 null。
     */
    static Ticker parseTicker(JsonNode item) {
        BigDecimal last = decimal(item, "last");
        BigDecimal open24h = decimal(item, "open24h");
        BigDecimal changePct = null;
        if (open24h.signum() > 0) {
            changePct = last.subtract(open24h)
                    .divide(open24h, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }
        BigDecimal volumeBase = decimal(item, "volCcy24h");
        return new Ticker(last, changePct, volumeBase, volumeBase.multiply(last),
                true, item.path("ts").asLong(0));
    }

    /**
     * 当前资金费率：rate 是预测费率，fundingTime 是它生效的结算时刻（即下一次结算），
     * nextFundingTime 是再下一期预计结算时刻，ts 是快照采集时刻。
     */
    public FundingSnapshot fundingRate(String instId) {
        JsonNode data = get("/api/v5/public/funding-rate?instId={i}", instId);
        JsonNode item = first(data, "funding-rate");
        return new FundingSnapshot(decimal(item, "fundingRate"),
                item.path("fundingTime").asLong(0),
                item.path("nextFundingTime").asLong(0),
                item.path("ts").asLong(0));
    }

    /** 历史费率取实际结算值 realizedRate（不是预测值 fundingRate），翻转为时间升序。 */
    public List<RatePoint> fundingRateHistory(String instId, int limit) {
        JsonNode data = get("/api/v5/public/funding-rate-history?instId={i}&limit={l}", instId, limit);
        List<RatePoint> rates = new ArrayList<>();
        for (JsonNode item : data) {
            // 接口倒序返回，翻转为升序；最新一期若尚未结算，realizedRate 为空，跳过
            String realized = item.path("realizedRate").asText("");
            if (realized.isBlank()) {
                continue;
            }
            rates.add(0, new RatePoint(new BigDecimal(realized),
                    item.path("fundingTime").asLong(0)));
        }
        return rates;
    }

    /** 指定 USDT 永续合约的当前持仓数量，单位为基础币（oiCcy）。 */
    public BigDecimal openInterest(String instId) {
        JsonNode data = get("/api/v5/public/open-interest?instType=SWAP&instId={i}", instId);
        return decimal(first(data, "open-interest"), "oiCcy");
    }

    /**
     * 指定合约的持仓量历史。官方列序为 [ts, oi(张), oiCcy(币), oiUsd(USD)]。
     * 使用 oiCcy，与 Binance 的 sumOpenInterest 对齐；不使用按币种汇总的接口。
     */
    public List<OiPoint> openInterestHistory(String instId, String period, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("OKX OI 历史 limit 必须在 1..100 之间");
        }
        JsonNode data = get("/api/v5/rubik/stat/contracts/open-interest-history?instId={i}&period={p}&limit={l}",
                instId, period, limit);
        return parseOiHistory(data, limit);
    }

    static List<OiPoint> parseOiHistory(JsonNode data, int limit) {
        if (!data.isArray()) {
            throw new ExchangeException(NAME, "持仓量历史响应必须是数组");
        }
        List<OiPoint> points = new ArrayList<>();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() < 4) {
                throw new ExchangeException(NAME, "持仓量历史行必须包含 [ts, oi, oiCcy, oiUsd]");
            }
            // 缺失或畸形字段直接失败，不能退回张数/USD 或静默跳过。
            long timestamp = Long.parseLong(row.get(0).asText());
            BigDecimal quantity = new BigDecimal(row.get(2).asText());
            if (timestamp <= 0 || quantity.signum() < 0) {
                throw new ExchangeException(NAME, "持仓量历史包含无效时间或负数量");
            }
            points.add(new OiPoint(timestamp, quantity));
        }
        points.sort(java.util.Comparator.comparingLong(OiPoint::timestamp));
        return List.copyOf(points.subList(Math.max(0, points.size() - limit), points.size()));
    }

    public OrderBook orderBook(String instId, int depth) {
        JsonNode data = get("/api/v5/market/books?instId={i}&sz={d}", instId, depth);
        JsonNode item = first(data, "books");
        // OKX 盘口数量是合约张数，不是基础币数量
        return new OrderBook(parseLevels(item.path("bids")), parseLevels(item.path("asks")),
                "contracts", item.path("ts").asLong(0));
    }

    public List<Trade> trades(String instId, int limit) {
        JsonNode data = get("/api/v5/market/trades?instId={i}&limit={l}", instId, limit);
        List<Trade> trades = new ArrayList<>();
        for (int i = data.size() - 1; i >= 0; i--) {
            JsonNode item = data.get(i);
            // sz 是合约张数，不是基础币数量
            trades.add(new Trade(
                    item.path("ts").asLong(),
                    decimal(item, "px"),
                    decimal(item, "sz"),
                    "buy".equalsIgnoreCase(item.path("side").asText()),
                    "contracts"));
        }
        return trades;
    }

    /** mark 与 index 是两次顺序请求，各自保留来源时间戳，供基差处给出时间差。 */
    public MarkPrice markPrice(String instId, String indexInstId) {
        JsonNode markData = get("/api/v5/public/mark-price?instType=SWAP&instId={i}", instId);
        JsonNode markItem = first(markData, "mark-price");
        JsonNode indexData = get("/api/v5/market/index-tickers?instId={i}", indexInstId);
        JsonNode indexItem = first(indexData, "index-tickers");
        return new MarkPrice(decimal(markItem, "markPx"), decimal(indexItem, "idxPx"), null, 0,
                markItem.path("ts").asLong(0), indexItem.path("ts").asLong(0));
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
