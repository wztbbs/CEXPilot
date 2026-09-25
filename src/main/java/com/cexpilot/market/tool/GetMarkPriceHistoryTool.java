package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.markprice.MarkPriceQueryResult;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.market.markprice.PriceType;
import com.cexpilot.market.model.Candle;
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
 * 标记价格/指数价格历史序列：指定时间范围（TimeSpec）+ 指定粒度的价格 K 线（开高低收）。
 * 区间统计（涨跌幅/最高/最低）归 get_mark_price_statistics，本 tool 不输出。
 */
@Component
public class GetMarkPriceHistoryTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final MarkPriceQueryService markPriceQueryService;

    public GetMarkPriceHistoryTool(MarketDataService market, MarkPriceQueryService markPriceQueryService) {
        super(market);
        this.markPriceQueryService = markPriceQueryService;
    }

    @Override
    public String name() {
        return "get_mark_price_history";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        PriceType priceType = PriceType.parse(args.path("price_type").asText("mark"));
        CandleInterval interval = CandleInterval.parse(args.path("interval").asText("5m"));
        TimeSpec spec = TimeSpecParser.parse(args.get("time"));
        boolean includeUnclosed = args.path("include_unclosed").asBoolean(false);

        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        MarkPriceQueryResult result = markPriceQueryService.query(
                userZone, spec, requestTime, exchange, base, priceType, interval,
                includeUnclosed);

        TimeRange requested = result.requested();
        TimeRange effective = result.effective();
        ZoneId zone = effective.timezone();
        List<Candle> candles = result.candles();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("price_type", priceType.code());
        facts.put("candle_interval", interval.code());
        facts.set("requested_range", SeriesFacts.rangeJson(requested));
        if (!effective.equals(requested)) {
            facts.set("effective_range", SeriesFacts.rangeJson(effective));
        }
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));

        facts.put("candle_count", candles.size());
        facts.putArray("candles_columns").add("open_time").add("open_price_usdt")
                .add("high_price_usdt").add("low_price_usdt").add("close_price_usdt");
        ArrayNode rows = facts.putArray("candles");
        for (Candle c : candles) {
            ArrayNode row = rows.addArray();
            row.add(Times.readable(c.openTime(), zone));
            row.add(c.open());
            row.add(c.high());
            row.add(c.low());
            row.add(c.close());
        }
        return facts;
    }
}
