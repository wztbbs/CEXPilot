package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.funding.FundingQueryResult;
import com.cexpilot.market.funding.FundingQueryService;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 资金费率区间统计（聚合指标类）：区间内已结算费率的均值、极值、正负期数及趋势。
 * 数据通过 FundingQueryService 获取（含覆盖核对），统计计算走 MarketCalculator；
 * 区间未完整覆盖（coverage.range_complete=false）时不输出 statistics，避免部分数据伪装成完整区间结果。
 */
@Component
public class GetFundingRateStatisticsTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final FundingQueryService fundingQueryService;

    public GetFundingRateStatisticsTool(MarketDataService market, FundingQueryService fundingQueryService) {
        super(market);
        this.fundingQueryService = fundingQueryService;
    }

    @Override
    public String name() {
        return "get_funding_rate_statistics";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        TimeSpec spec = TimeSpecParser.parse(args.get("time"));

        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        FundingQueryResult result = fundingQueryService.query(userZone, spec, requestTime, exchange, base);
        ZoneId zone = result.range().timezone();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("funding_interval_hours", result.intervalMs() / 3_600_000L);
        facts.set("requested_range", SeriesFacts.rangeJson(result.range()));
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));

        List<BigDecimal> rates = result.points().stream().map(FundingRatePoint::rate).toList();
        MarketCalculator.FundingStats stats = result.coverage().rangeComplete()
                ? MarketCalculator.fundingStats(rates) : null;
        if (stats != null) {
            ObjectNode node = facts.putObject("statistics");
            node.put("mean", MarketCalculator.roundPlain(stats.mean(), 8));
            node.put("min", MarketCalculator.roundPlain(stats.min(), 8));
            node.put("max", MarketCalculator.roundPlain(stats.max(), 8));
            node.put("positive_count", stats.positiveCount());
            node.put("negative_count", stats.negativeCount());
            node.put("zero_count", stats.zeroCount());
            node.put("trend", stats.trend());
            node.put("trend_method", "按实际取得的" + stats.periodCount()
                    + "期后半段均值 vs 前半段均值，差值小于 0.005% 判为 flat；不足 4 期为 unknown");
            node.put("period_count", stats.periodCount());
            ObjectNode actualRange = node.putObject("actual_range");
            actualRange.put("start_inclusive",
                    Times.readable(result.points().get(0).fundingTime(), zone));
            actualRange.put("end_inclusive",
                    Times.readable(result.points().get(result.points().size() - 1).fundingTime(), zone));
        } else {
            facts.put("statistics_omitted", "区间未完整覆盖（见 coverage.range_complete），不输出区间统计");
        }
        return facts;
    }
}
