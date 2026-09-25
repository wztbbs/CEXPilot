package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.markprice.MarkPriceQueryResult;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.market.markprice.PriceType;
import com.cexpilot.market.series.BoundaryMode;
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

/**
 * 标记价格/指数价格区间统计（聚合指标类）：区间涨跌幅、最高价和最低价。
 * 价格 K 线接口不提供成交量，统计不含成交量类指标。
 * 区间未完整覆盖（coverage.range_complete=false）时不输出 statistics，避免部分数据伪装成完整区间结果。
 */
@Component
public class GetMarkPriceStatisticsTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final MarkPriceQueryService markPriceQueryService;

    public GetMarkPriceStatisticsTool(MarketDataService market, MarkPriceQueryService markPriceQueryService) {
        super(market);
        this.markPriceQueryService = markPriceQueryService;
    }

    @Override
    public String name() {
        return "get_mark_price_statistics";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        PriceType priceType = PriceType.parse(args.path("price_type").asText("mark"));
        CandleInterval interval = CandleInterval.parse(args.path("interval").asText("5m"));
        TimeSpec spec = TimeSpecParser.parse(args.get("time"));
        BoundaryMode boundaryMode = BoundaryMode.parse(args.path("boundary_mode").asText("exact"));
        boolean includeUnclosed = args.path("include_unclosed").asBoolean(false);

        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        MarkPriceQueryResult result = markPriceQueryService.query(
                userZone, spec, requestTime, exchange, base, priceType, interval,
                boundaryMode, includeUnclosed);

        TimeRange requested = result.requested();
        TimeRange effective = result.effective();
        ZoneId zone = effective.timezone();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("price_type", priceType.code());
        facts.put("candle_interval", interval.code());
        facts.put("boundary_mode", boundaryMode.code());
        facts.set("requested_range", SeriesFacts.rangeJson(requested));
        if (!effective.equals(requested)) {
            facts.set("effective_range", SeriesFacts.rangeJson(effective));
        }
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));

        MarketCalculator.PriceChange change = result.coverage().rangeComplete()
                ? MarketCalculator.priceChange(result.candles()) : null;
        if (change != null) {
            ObjectNode node = facts.putObject("statistics");
            node.put("start_price", change.startPrice());
            node.put("end_price", change.endPrice());
            node.put("change_pct", change.changePct());
            node.put("high", change.high());
            node.put("low", change.low());
            node.put("candle_count", change.candleCount());
            ObjectNode actualRange = node.putObject("actual_range");
            actualRange.put("start_inclusive",
                    Times.readable(result.candles().get(0).openTime(), zone));
            actualRange.put("end_exclusive",
                    Times.readable(result.coverage().coveredUntilMs(), zone));
        } else {
            facts.put("statistics_omitted", "区间未完整覆盖（见 coverage.range_complete），不输出区间统计");
        }
        return facts;
    }
}
