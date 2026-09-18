package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.Trade;
import com.cexpilot.runtime.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 最近逐笔成交 + 已计算的主动买卖统计。
 */
@Component
public class GetRecentTradesTool extends AbstractMarketTool {

    private static final int OUTPUT_TRADES = 20;

    public GetRecentTradesTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_recent_trades";
    }

    @Override
    public String description() {
        return "获取最近逐笔成交记录，并返回已计算的主动买入占比（buy_volume_ratio 接近 1 说明买方主动性强）。用于判断短期多空力量";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.parse("""
                {"type": "object", "properties": {
                %s,
                "limit": {"type": "integer", "description": "成交条数，默认 50，最大 100"}
                }, "required": ["exchange", "symbol"]}
                """.formatted(exchangeSymbolSchema()));
    }

    @Override
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        int limit = args.path("limit").asInt(50);
        List<Trade> trades = market.recentTrades(exchange, base, limit);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);

        MarketCalculator.TradesSummary summary = MarketCalculator.tradesSummary(trades);
        if (summary != null) {
            facts.put("trade_count", summary.count());
            facts.put("buy_count", summary.buyCount());
            if (summary.buyVolumeRatio() != null) {
                facts.put("buy_volume_ratio", summary.buyVolumeRatio());
            }
        }

        ArrayNode rows = facts.putArray("recent_trades");
        trades.stream().limit(OUTPUT_TRADES).forEach(trade -> {
            ArrayNode row = rows.addArray();
            row.add(trade.time());
            row.add(trade.price());
            row.add(trade.qty());
            row.add(trade.buyAggressor() ? "buy" : "sell");
        });
        return facts;
    }
}
