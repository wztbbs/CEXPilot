package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.trade.TradeQueryResult;
import com.cexpilot.market.trade.TradeQueryService;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 成交流量区间统计（聚合指标类）：区间内主动买卖成交量、成交额及占比。
 * 笔数口径逐所不同（币安=聚合成交、OKX=逐笔），不可跨所比较。
 * 分页被中止（complete=false）时不输出 statistics，避免部分数据伪装成完整区间。
 */
@Component
public class GetTradeFlowStatisticsTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final TradeQueryService tradeQueryService;

    public GetTradeFlowStatisticsTool(MarketDataService market, TradeQueryService tradeQueryService) {
        super(market);
        this.tradeQueryService = tradeQueryService;
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
        TradeQueryResult result = tradeQueryService.query(userZone, spec, requestTime, exchange, base);
        ZoneId zone = result.range().timezone();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.set("requested_range", SeriesFacts.rangeJson(result.range()));
        facts.put("actual_count", result.trades().size());
        facts.put("complete", result.complete());
        if (result.abortReason() != null) {
            facts.put("abort_reason", result.abortReason());
        }

        MarketCalculator.TradeFlowStats stats = result.complete()
                ? MarketCalculator.tradeFlowStats(result.trades()) : null;
        if (stats != null) {
            ObjectNode node = facts.putObject("statistics");
            node.put("trade_count", stats.tradeCount());
            node.put("trade_count_kind", exchange == Exchange.BINANCE
                    ? "聚合成交笔数（一笔聚合可能含多笔原始成交）" : "逐笔成交笔数");
            node.put("buy_volume", stats.buyVolume());
            node.put("sell_volume", stats.sellVolume());
            node.put("buy_quote_volume", stats.buyQuoteVolume());
            node.put("sell_quote_volume", stats.sellQuoteVolume());
            if (stats.buyVolumeRatio() != null) {
                node.put("buy_volume_ratio", stats.buyVolumeRatio());
            }
            ObjectNode actualRange = node.putObject("actual_range");
            actualRange.put("first_trade_time",
                    Times.readable(result.trades().get(0).timestamp(), zone));
            actualRange.put("last_trade_time",
                    Times.readable(result.trades().get(result.trades().size() - 1).timestamp(), zone));
        } else {
            facts.put("statistics_omitted", "数据不完整（分页中止），不输出区间统计");
        }
        return facts;
    }
}
