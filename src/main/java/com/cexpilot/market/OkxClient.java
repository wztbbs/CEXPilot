package com.cexpilot.market;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.model.FundingSnapshot;
import com.cexpilot.market.model.MarkPrice;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.model.OpenInterestInfo;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.TakerVolumePoint;
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
        factory.setReadTimeout(Duration.ofSeconds(20));
        String baseUrl = config.getOkx().getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 按区间查询 K 线：after=只返回比此 ts 更早的记录，before=只返回比此 ts 更新的记录，
     * 接口倒序返回（最新在前），parseCandles 翻转为升序。单页上限 300 根；跨页拉取由调用方（KlineSource）负责。
     * 注意：本接口只覆盖最近 1,440 根，更早的历史必须用 {@link #historyCandles}。
     */
    public List<Candle> candles(String instId, String bar, long beforeTs, long afterTs, int limit) {
        JsonNode data = get("/api/v5/market/candles?instId={i}&bar={b}&before={bf}&after={af}&limit={l}",
                instId, bar, beforeTs, afterTs, limit);
        return parseCandles(data);
    }

    /**
     * 历史 K 线（最近 1,440 根之前的部分），参数与返回结构同 {@link #candles}，单页上限 100 根。
     */
    public List<Candle> historyCandles(String instId, String bar, long beforeTs, long afterTs, int limit) {
        JsonNode data = get("/api/v5/market/history-candles?instId={i}&bar={b}&before={bf}&after={af}&limit={l}",
                instId, bar, beforeTs, afterTs, limit);
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

    /**
     * 区间历史费率：取实际结算值 realizedRate（不是预测值 fundingRate），翻转为时间升序。
     * after=只返回比此 ts 更早的结算，before=只返回比此 ts 更新的结算；单页上限 100 条。
     */
    public List<FundingRatePoint> fundingRateHistory(String instId, long beforeTs, long afterTs, int limit) {
        JsonNode data = get("/api/v5/public/funding-rate-history?instId={i}&before={bf}&after={af}&limit={l}",
                instId, beforeTs, afterTs, limit);
        List<FundingRatePoint> rates = new ArrayList<>();
        for (JsonNode item : data) {
            // 接口倒序返回，翻转为升序；最新一期若尚未结算，realizedRate 为空，跳过
            String realized = item.path("realizedRate").asText("");
            if (realized.isBlank()) {
                continue;
            }
            rates.add(0, new FundingRatePoint(new BigDecimal(realized),
                    item.path("fundingTime").asLong(0)));
        }
        return rates;
    }

    /** 指定 USDT 永续合约的当前持仓量快照：oiCcy 单位为基础币，ts 为数据时间。 */
    public OpenInterestInfo openInterestSnapshot(String instId) {
        JsonNode data = get("/api/v5/public/open-interest?instType=SWAP&instId={i}", instId);
        JsonNode item = first(data, "open-interest");
        return new OpenInterestInfo(decimal(item, "oiCcy"), null,
                item.path("ts").asLong(0), System.currentTimeMillis());
    }

    /**
     * 区间持仓量历史：begin/end 限定采样时间范围，接口倒序返回（最新在前），翻转为升序。
     * 官方列序为 [ts, oi(张), oiCcy(币), oiUsd(USD)]。使用 oiCcy，与 Binance 的
     * sumOpenInterest 对齐；不使用按币种汇总的接口。单页上限 100 条；跨页由调用方（OiSource）负责。
     */
    public List<OiPoint> openInterestHistory(String instId, String period,
                                             long beginMs, long endMs, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("OKX OI 历史 limit 必须在 1..100 之间");
        }
        JsonNode data = get("/api/v5/rubik/stat/contracts/open-interest-history?instId={i}&period={p}&begin={bg}&end={e}&limit={l}",
                instId, period, beginMs, endMs, limit);
        return parseOiHistory(data);
    }

    static List<OiPoint> parseOiHistory(JsonNode data) {
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
        return List.copyOf(points);
    }

    /**
     * 合约级 taker 主动买卖成交量统计（rubik taker-volume-contract）：5m 周期，
     * 列序为 [ts, buyVol, sellVol]，单位为合约张数（基础币换算由调用方按 ctVal 处理），
     * begin/end 限定周期起点范围（begin 不含等值），接口倒序返回（最新在前），翻转为升序。
     * 单页上限 100 条；跨页拉取由调用方（TakerVolumeSource）负责。
     */
    public List<TakerVolumePoint> takerVolumeContract(String instId, String period,
                                                      long beginMs, long endMs, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("OKX taker 成交量 limit 必须在 1..100 之间");
        }
        JsonNode data = get("/api/v5/rubik/stat/taker-volume-contract?instId={i}&period={p}&begin={bg}&end={e}&limit={l}",
                instId, period, beginMs, endMs, limit);
        return parseTakerVolume(data);
    }

    static List<TakerVolumePoint> parseTakerVolume(JsonNode data) {
        if (!data.isArray()) {
            throw new ExchangeException(NAME, "taker 成交量响应必须是数组");
        }
        List<TakerVolumePoint> points = new ArrayList<>();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() < 3) {
                throw new ExchangeException(NAME, "taker 成交量行必须包含 [ts, buyVol, sellVol]");
            }
            // 缺失或畸形字段直接失败，不能静默跳过
            long timestamp = Long.parseLong(row.get(0).asText());
            BigDecimal buyVolume = new BigDecimal(row.get(1).asText());
            BigDecimal sellVolume = new BigDecimal(row.get(2).asText());
            if (timestamp <= 0 || buyVolume.signum() < 0 || sellVolume.signum() < 0) {
                throw new ExchangeException(NAME, "taker 成交量包含无效时间或负数量");
            }
            points.add(new TakerVolumePoint(timestamp, buyVolume, sellVolume));
        }
        points.sort(java.util.Comparator.comparingLong(TakerVolumePoint::timestamp));
        return List.copyOf(points);
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

    /** 标记价格 K 线（最近 1440 根），倒序返回，翻转为升序。 */
    public List<Candle> markPriceCandles(String instId, String bar, long beforeTs, long afterTs, int limit) {
        return parseMarkPriceCandles(get("/api/v5/market/mark-price-candles?instId={i}&bar={b}&before={bf}&after={af}&limit={l}",
                instId, bar, beforeTs, afterTs, limit));
    }

    /** 标记价格历史 K 线（最近 1440 根之前），倒序返回，翻转为升序。 */
    public List<Candle> historyMarkPriceCandles(String instId, String bar, long beforeTs, long afterTs, int limit) {
        return parseMarkPriceCandles(get("/api/v5/market/history-mark-price-candles?instId={i}&bar={b}&before={bf}&after={af}&limit={l}",
                instId, bar, beforeTs, afterTs, limit));
    }

    /** 指数价格 K 线（最近 1440 根），倒序返回，翻转为升序。 */
    public List<Candle> indexCandles(String instId, String bar, long beforeTs, long afterTs, int limit) {
        return parseMarkPriceCandles(get("/api/v5/market/index-candles?instId={i}&bar={b}&before={bf}&after={af}&limit={l}",
                instId, bar, beforeTs, afterTs, limit));
    }

    /** 指数价格历史 K 线（最近 1440 根之前），倒序返回，翻转为升序。 */
    public List<Candle> historyIndexCandles(String instId, String bar, long beforeTs, long afterTs, int limit) {
        return parseMarkPriceCandles(get("/api/v5/market/history-index-candles?instId={i}&bar={b}&before={bf}&after={af}&limit={l}",
                instId, bar, beforeTs, afterTs, limit));
    }

    /**
     * 标记/指数价格 K 线：6 列 [ts, o, h, l, c, confirm]，无成交量（volume/quoteVolume=null），
     * confirm 映射完结状态；接口倒序返回，翻转为升序。
     */
    static List<Candle> parseMarkPriceCandles(JsonNode data) {
        List<Candle> candles = new ArrayList<>();
        for (int i = data.size() - 1; i >= 0; i--) {
            JsonNode row = data.get(i);
            candles.add(new Candle(
                    row.get(0).asLong(),
                    new BigDecimal(row.get(1).asText()),
                    new BigDecimal(row.get(2).asText()),
                    new BigDecimal(row.get(3).asText()),
                    new BigDecimal(row.get(4).asText()),
                    null, null,
                    row.size() > 5 ? Boolean.valueOf("1".equals(row.get(5).asText())) : null));
        }
        return candles;
    }

    /**
     * 区间历史逐笔成交：倒序返回（最新在前），单页上限 100。
     * type=1（默认）：after/before 为 tradeId，after=<tradeId> 翻更早、before=<tradeId> 翻更新；
     * type=2：after/before 为毫秒时间戳，用于按时间锚定首页（同一毫秒多笔时翻页必须用 type=1）。
     * sz 为合约张数（基础币换算由调用方按 ctVal 处理）；side 为主动方。
     * 跨页拉取由调用方（TradeSource）负责。
     */
    public List<TradePoint> historyTrades(String instId, String type,
                                          String beforeTradeId, String afterValue, int limit) {
        StringBuilder uri = new StringBuilder("/api/v5/market/history-trades?instId={i}&limit={l}");
        List<Object> vars = new ArrayList<>(List.of(instId, limit));
        if (type != null) {
            uri.append("&type={t}");
            vars.add(type);
        }
        if (beforeTradeId != null) {
            uri.append("&before={bf}");
            vars.add(beforeTradeId);
        }
        if (afterValue != null) {
            uri.append("&after={af}");
            vars.add(afterValue);
        }
        JsonNode data = get(uri.toString(), vars.toArray());
        return parseHistoryTrades(data);
    }

    static List<TradePoint> parseHistoryTrades(JsonNode data) {
        List<TradePoint> trades = new ArrayList<>();
        // 倒序返回，翻转为升序；sz 保留张数原值，换算在 source 做
        for (int i = data.size() - 1; i >= 0; i--) {
            JsonNode item = data.get(i);
            trades.add(new TradePoint(
                    item.path("tradeId").asText(),
                    item.path("ts").asLong(),
                    decimal(item, "px"),
                    decimal(item, "sz"),
                    "buy".equalsIgnoreCase(item.path("side").asText())));
        }
        return trades;
    }

    /** 合约面值（每张合约对应的基础币数量，如 BTC-USDT-SWAP 为 0.01）。 */
    public BigDecimal instrumentCtVal(String instId) {
        JsonNode data = get("/api/v5/public/instruments?instType=SWAP&instId={i}", instId);
        return decimal(first(data, "instruments"), "ctVal");
    }

    /** 分页查询会串行拉多页大响应体，网络/代理偶发中断属常态；I/O 类失败重试，HTTP 状态错误与业务错误码不重试。 */
    private static final int MAX_ATTEMPTS = 3;

    private JsonNode get(String uri, Object... vars) {
        for (int attempt = 1; ; attempt++) {
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
                log.warn("okx 请求异常 {} {}ms 第{}次: {}", uri, System.currentTimeMillis() - start,
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
                    new BigDecimal(row.get(6).asText()),
                    // 第 8 列 volCcyQuote 为 USDT 成交额
                    new BigDecimal(row.get(7).asText()),
                    // 第 9 列 confirm："1"=已完结，"0"=未完结（OHLCV 仍是部分值，不能按时间推断覆盖）
                    row.size() > 8 ? Boolean.valueOf("1".equals(row.get(8).asText())) : null));
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
