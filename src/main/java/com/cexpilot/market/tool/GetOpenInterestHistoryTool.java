package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.oi.OiQueryResult;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.time.OiInterval;
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
 * 持仓量历史序列：指定时间范围（TimeSpec）+ 指定采样粒度，返回逐点持仓量（基础币）。
 * 区间统计（期初期末/变化率/极值）归 get_open_interest_statistics，本 tool 不输出。
 */
@Component
public class GetOpenInterestHistoryTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final OiQueryService oiQueryService;

    public GetOpenInterestHistoryTool(MarketDataService market, OiQueryService oiQueryService) {
        super(market);
        this.oiQueryService = oiQueryService;
    }

    @Override
    public String name() {
        return "get_open_interest_history";
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
        List<OiPoint> points = result.points();

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

        facts.put("point_count", points.size());
        facts.putArray("oi_series_columns").add("time").add("open_interest");
        ArrayNode rows = facts.putArray("oi_series");
        for (OiPoint point : points) {
            ArrayNode row = rows.addArray();
            row.add(Times.readable(point.timestamp(), zone));
            row.add(point.oi());
        }
        return facts;
    }
}
