package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.taker.TakerVolumeQueryResult;
import com.cexpilot.market.taker.TakerVolumeQueryService;
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
 * 成交流量区间统计（聚合指标类）：区间内主动买/卖成交量（基础币）及主动买占比。
 * 数据来自交易所官方 taker 统计接口（币安 takerlongshortRatio / OKX taker-volume-contract，
 * 5m 周期序列求和），不做逐笔翻页聚合；不提供成交额与成交笔数。
 * 数据通过 TakerVolumeQueryService 获取（含覆盖核对），统计计算走 MarketCalculator；
 * 区间未完整覆盖（coverage.range_complete=false）时不输出 statistics，避免部分数据伪装成完整区间结果。
 */
@Component
public class GetTradeFlowStatisticsTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final TakerVolumeQueryService takerVolumeQueryService;

    public GetTradeFlowStatisticsTool(MarketDataService market, TakerVolumeQueryService takerVolumeQueryService) {
        super(market);
        this.takerVolumeQueryService = takerVolumeQueryService;
    }

    @Override
    public String name() {
        return "get_trade_flow_statistics";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        Exchange exchange = parseExchange(args);
        String base = parseBase(args);
        TimeSpec spec = TimeSpecParser.parse(args.get("time"));

        ZoneId userZone = ctx != null && ctx.timezone() != null ? ctx.timezone() : DEFAULT_ZONE;
        Instant requestTime = ctx != null && ctx.requestTime() != null
                ? ctx.requestTime() : Instant.now();
        TakerVolumeQueryResult result = takerVolumeQueryService.query(
                userZone, spec, requestTime, exchange, base);

        TimeRange requested = result.requested();
        TimeRange effective = result.effective();
        ZoneId zone = effective.timezone();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("unit", base);
        facts.put("aggregation_interval", "5m");
        facts.set("requested_range", SeriesFacts.rangeJson(requested));
        if (!effective.equals(requested)) {
            facts.set("effective_range", SeriesFacts.rangeJson(effective));
        }
        facts.set("coverage", SeriesFacts.coverageJson(result.coverage(), zone));

        MarketCalculator.TakerFlowStats stats = result.coverage().rangeComplete()
                ? MarketCalculator.takerFlowStats(result.points()) : null;
        if (stats != null) {
            ObjectNode node = facts.putObject("statistics");
            node.put("buy_volume", stats.buyVolume());
            node.put("sell_volume", stats.sellVolume());
            if (stats.buyVolumeRatio() != null) {
                node.put("buy_volume_ratio", stats.buyVolumeRatio());
            }
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
