package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.funding.FundingQueryResult;
import com.cexpilot.market.funding.FundingQueryService;
import com.cexpilot.market.funding.FundingRecentResult;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.policy.SampleQueryPolicy;
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
 * 资金费率历史序列：指定区间的已结算费率（time 模式，含覆盖核对），
 * 或最近 N 期已结算费率（count 模式，样本语义）。两种模式恰好选一。
 * 每期保留结算时间；费率以小数表示（0.0001 = 0.01%）。
 */
@Component
public class GetFundingRateHistoryTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final FundingQueryService fundingQueryService;

    public GetFundingRateHistoryTool(MarketDataService market, FundingQueryService fundingQueryService) {
        super(market);
        this.fundingQueryService = fundingQueryService;
    }

    @Override
    public String name() {
        return "get_funding_rate_history";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        JsonNode timeNode = args.get("time");
        if (args.has("time") == args.has("count")) {
            throw new IllegalArgumentException("time 和 count 必须恰好提供一个");
        }
        int count = args.has("count") ? SampleQueryPolicy.count(args, "count", null, 100) : 0;
        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        return timeNode != null
                ? byTimeRange(exchange, base, timeNode, userZone, requestTime)
                : byCount(exchange, base, count, userZone, requestTime);
    }

    private JsonNode byTimeRange(Exchange exchange, String base, JsonNode timeNode,
                                 ZoneId userZone, Instant requestTime) {
        TimeSpec spec = TimeSpecParser.parse(timeNode);
        FundingQueryResult result = fundingQueryService.query(userZone, spec, requestTime, exchange, base);
        ZoneId zone = result.range().timezone();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("mode", "time_range");
        facts.put("funding_interval_hours", result.intervalMs() / 3_600_000L);
        facts.set("requested_range", SeriesFacts.rangeJson(result.range()));
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));
        facts.put("period_count", result.points().size());
        addRates(facts, result.points(), zone);
        return facts;
    }

    private JsonNode byCount(Exchange exchange, String base, int count,
                             ZoneId userZone, Instant requestTime) {
        FundingRecentResult result = fundingQueryService.queryRecent(exchange, base, count, requestTime);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("mode", "recent_count");
        facts.put("funding_interval_hours", result.intervalMs() / 3_600_000L);
        facts.put("requested_count", count);
        facts.put("actual_count", result.points().size());
        facts.put("sample_complete", result.points().size() == count);
        if (result.points().size() < count) {
            facts.put("actual_count_note",
                    "实际只获取到 " + result.points().size() + " 期，非完整 " + count + " 期");
        }
        addRates(facts, result.points(), userZone);
        return facts;
    }

    private static void addRates(ObjectNode facts, List<FundingRatePoint> points, ZoneId zone) {
        facts.putArray("rates_columns").add("settle_time").add("rate");
        ArrayNode rates = facts.putArray("rates");
        for (FundingRatePoint point : points) {
            ArrayNode row = rates.addArray();
            row.add(Times.readable(point.fundingTime(), zone));
            row.add(MarketCalculator.roundPlain(point.rate(), 8));
        }
    }
}
