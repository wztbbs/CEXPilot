package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.Trade;
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
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        int limit = args.path("limit").intValue();
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

        facts.put("sample_basis", "最近N笔成交，不代表固定时间窗口；统计基于全部样本，明细最多20笔");
        facts.putArray("recent_trades_columns").add("time_ms").add("price_usdt")
                .add("quantity").add("aggressor_side");
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
