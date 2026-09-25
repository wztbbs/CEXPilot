package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.TradePoint;
import com.cexpilot.market.trade.TradeQueryResult;
import com.cexpilot.market.trade.TradeQueryService;
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
 * 成交历史序列：指定时间区间的成交明细。口径逐所标注：
 * 币安为 aggTrades 聚合成交（一笔聚合可能含多笔原始成交），OKX 为逐笔成交。
 * 逐笔没有预期序列，complete=false 表示分页被中止、结果为区间部分数据。
 */
@Component
public class GetTradeHistoryTool extends AbstractMarketTool {

    /** 请求未携带时区时的回落值（与 facts 渲染的历史口径一致）。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);
    private static final int OUTPUT_LIMIT = 500;

    private final TradeQueryService tradeQueryService;

    public GetTradeHistoryTool(MarketDataService market, TradeQueryService tradeQueryService) {
        super(market);
        this.tradeQueryService = tradeQueryService;
    }

    @Override
    public String name() {
        return "get_trade_history";
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
        List<TradePoint> trades = result.trades();

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("trade_source_kind", exchange == Exchange.BINANCE
                ? "aggTrades 聚合成交（一笔聚合可能含多笔原始成交）" : "逐笔成交");
        facts.set("requested_range", SeriesFacts.rangeJson(result.range()));
        facts.put("actual_count", trades.size());
        facts.put("complete", result.complete());
        if (result.abortReason() != null) {
            facts.put("abort_reason", result.abortReason());
            facts.put("partial_result_note", "结果为区间的部分数据，不得当作完整区间成交");
        }
        facts.put("first_trade_time", Times.readable(trades.get(0).timestamp(), zone));
        facts.put("last_trade_time",
                Times.readable(trades.get(trades.size() - 1).timestamp(), zone));

        facts.putArray("trades_columns").add("time").add("price_usdt").add("qty_base").add("taker_side");
        ArrayNode rows = facts.putArray("trades");
        int to = Math.min(trades.size(), OUTPUT_LIMIT);
        if (trades.size() > OUTPUT_LIMIT) {
            facts.put("trades_note", "明细仅输出前 " + OUTPUT_LIMIT + " 笔（共 " + trades.size() + " 笔）");
        }
        for (int i = 0; i < to; i++) {
            TradePoint trade = trades.get(i);
            ArrayNode row = rows.addArray();
            row.add(Times.readable(trade.timestamp(), zone));
            row.add(trade.price());
            row.add(trade.qty());
            row.add(trade.takerBuy() ? "buy" : "sell");
        }
        return facts;
    }
}
