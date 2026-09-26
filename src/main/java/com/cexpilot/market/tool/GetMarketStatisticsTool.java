package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.kline.KlineQueryResult;
import com.cexpilot.market.kline.KlineQueryService;
import com.cexpilot.market.model.Candle;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 区间统计（聚合指标类）：查询区间开高低收、涨跌幅、成交量和成交额，不返回 K 线明细。
 * 数据通过 KlineQueryService 获取（含覆盖核对），统计计算走 MarketCalculator；
 * 区间未完整覆盖（coverage.range_complete=false）时不输出 statistics，避免部分数据伪装成完整区间结果。
 */
@Component
public class GetMarketStatisticsTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final KlineQueryService klineQueryService;

    public GetMarketStatisticsTool(MarketDataService market, KlineQueryService klineQueryService) {
        super(market);
        this.klineQueryService = klineQueryService;
    }

    @Override
    public String name() {
        return "get_market_statistics";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        CandleInterval interval = args.has("interval")
                ? CandleInterval.parse(args.get("interval").asText()) : null;
        TimeSpec spec = TimeSpecParser.parse(args.get("time"));
        boolean includeUnclosed = args.path("include_unclosed").asBoolean(false);

        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        KlineQueryResult result = klineQueryService.query(
                userZone, spec, requestTime, exchange, base, interval, includeUnclosed);

        TimeRange requested = result.requested().range();
        TimeRange effective = result.effective().range();
        ZoneId zone = effective.timezone();
        List<Candle> candles = result.candles();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("candle_interval", result.effective().interval().code());
        facts.put("interval_source", interval == null ? "automatic" : "explicit");
        facts.set("requested_range", SeriesFacts.rangeJson(requested));
        if (!effective.equals(requested)) {
            facts.set("effective_range", SeriesFacts.rangeJson(effective));
        }
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));

        MarketCalculator.RangeStats stats = result.coverage().rangeComplete()
                ? MarketCalculator.rangeStats(candles) : null;
        if (stats != null) {
            ObjectNode node = facts.putObject("statistics");
            node.put("open", stats.open());
            node.put("close", stats.close());
            node.put("high", stats.high());
            node.put("low", stats.low());
            node.put("change_pct", stats.changePct());
            node.put("volume", stats.volume());
            if (stats.quoteVolume() != null) {
                node.put("quote_volume", stats.quoteVolume());
            }
            node.put("candle_count", stats.candleCount());
            ObjectNode actualRange = node.putObject("actual_range");
            actualRange.put("start_inclusive", Times.readable(candles.get(0).openTime(), zone));
            actualRange.put("end_exclusive",
                    Times.readable(result.coverage().coveredUntilMs(), zone));
        } else {
            facts.put("statistics_omitted", "区间未完整覆盖（见 coverage.range_complete），不输出区间统计");
        }
        return facts;
    }
}
