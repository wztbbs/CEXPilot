package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.oi.OiQueryResult;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.time.OiInterval;
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
 * 持仓量区间统计（聚合指标类）：期初/期末持仓量、变化量、变化率及采样极值（含极值时间）。
 * 数据通过 OiQueryService 获取（含覆盖核对），统计计算走 MarketCalculator；
 * 区间未完整覆盖（coverage.range_complete=false）时不输出 statistics，避免部分数据伪装成完整区间结果。
 */
@Component
public class GetOpenInterestStatisticsTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final OiQueryService oiQueryService;

    public GetOpenInterestStatisticsTool(MarketDataService market, OiQueryService oiQueryService) {
        super(market);
        this.oiQueryService = oiQueryService;
    }

    @Override
    public String name() {
        return "get_open_interest_statistics";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        OiInterval interval = OiInterval.parse(args.path("interval").asText("1h"));
        TimeSpec spec = TimeSpecParser.parse(args.get("time"));
        boolean includeUnclosed = args.path("include_unclosed").asBoolean(false);

        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        OiQueryResult result = oiQueryService.query(
                userZone, spec, requestTime, exchange, base, interval, includeUnclosed);

        TimeRange requested = result.requested().range();
        TimeRange effective = result.effective().range();
        ZoneId zone = effective.timezone();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("oi_interval", interval.code());
        facts.put("unit", base);
        facts.set("requested_range", SeriesFacts.rangeJson(requested));
        if (!effective.equals(requested)) {
            facts.set("effective_range", SeriesFacts.rangeJson(effective));
        }
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));

        MarketCalculator.OiStats stats = result.coverage().rangeComplete()
                ? MarketCalculator.oiStats(result.points()) : null;
        if (stats != null) {
            ObjectNode node = facts.putObject("statistics");
            node.put("start_oi", stats.startOi());
            node.put("end_oi", stats.endOi());
            node.put("change", stats.change());
            if (stats.changePct() != null) {
                node.put("change_pct", stats.changePct());
            }
            node.put("max_oi", stats.maxOi());
            node.put("max_time", Times.readable(stats.maxTime(), zone));
            node.put("min_oi", stats.minOi());
            node.put("min_time", Times.readable(stats.minTime(), zone));
            node.put("point_count", stats.pointCount());
            ObjectNode actualRange = node.putObject("actual_range");
            actualRange.put("start_inclusive",
                    Times.readable(result.points().get(0).timestamp(), zone));
            actualRange.put("end_inclusive",
                    Times.readable(result.points().get(result.points().size() - 1).timestamp(), zone));
        } else {
            facts.put("statistics_omitted", "区间未完整覆盖（见 coverage.range_complete），不输出区间统计");
        }
        return facts;
    }
}
