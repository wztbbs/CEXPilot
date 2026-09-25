package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.kline.KlineQueryResult;
import com.cexpilot.market.kline.KlineQueryService;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.BoundaryMode;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * K 线历史序列查询：指定时间范围（TimeSpec）+ 指定粒度，返回逐根 K 线明细。
 * 区间统计（开高低收/涨跌幅/成交量/成交额）归 get_market_statistics，本 tool 不输出。
 * 本类只解析参数、调用 KlineQueryService、组装 facts；
 * 分页、时间戳运算和缺口检查都在 service 及其协作组件里。
 */
@Component
public class GetKlinesTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final KlineQueryService klineQueryService;

    public GetKlinesTool(MarketDataService market, KlineQueryService klineQueryService) {
        super(market);
        this.klineQueryService = klineQueryService;
    }

    @Override
    public String name() {
        return "get_klines";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        CandleInterval interval = CandleInterval.parse(args.path("interval").asText("5m"));
        TimeSpec spec = TimeSpecParser.parse(args.get("time"));
        BoundaryMode boundaryMode = BoundaryMode.parse(args.path("boundary_mode").asText("exact"));
        boolean includeUnclosed = args.path("include_unclosed").asBoolean(false);

        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        // 请求上下文未携带固定基准时回落为当前时刻（单工具调用内仍是一致的）
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        KlineQueryResult result = klineQueryService.query(
                userZone, spec, requestTime, exchange, base, interval, boundaryMode, includeUnclosed);

        TimeRange requested = result.requested().range();
        TimeRange effective = result.effective().range();
        ZoneId zone = effective.timezone();
        List<Candle> candles = result.candles();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("candle_interval", interval.code());
        facts.put("boundary_mode", boundaryMode.code());
        facts.set("requested_range", SeriesFacts.rangeJson(requested));
        if (!effective.equals(requested)) {
            facts.set("effective_range", SeriesFacts.rangeJson(effective));
        }
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));

        facts.put("candle_count", candles.size());
        facts.putArray("candles_columns").add("open_time").add("open_price_usdt")
                .add("high_price_usdt").add("low_price_usdt").add("close_price_usdt").add("volume");
        ArrayNode rows = facts.putArray("candles");
        for (Candle c : candles) {
            ArrayNode row = rows.addArray();
            row.add(Times.readable(c.openTime(), zone));
            row.add(c.open());
            row.add(c.high());
            row.add(c.low());
            row.add(c.close());
            row.add(c.volume());
        }
        return facts;
    }
}
