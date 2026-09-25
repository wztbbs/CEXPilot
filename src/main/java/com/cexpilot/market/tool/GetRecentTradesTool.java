package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.Trade;
import com.cexpilot.market.policy.SampleQueryPolicy;
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

    public GetRecentTradesTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_recent_trades";
    }

    @Override
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        int limit = SampleQueryPolicy.count(args, "limit", 50, 100);
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

        // OKX 数量是合约张数，Binance 是基础币；同一交易所内单位一致，占比不受影响
        boolean contracts = !trades.isEmpty() && "contracts".equals(trades.get(0).qtyUnit());
        facts.put("quantity_unit", contracts ? "contracts（合约张数，未换算为币数）" : base + "（基础币）");
        facts.put("requested_count", limit);
        facts.put("actual_count", trades.size());
        facts.put("sample_complete", trades.size() == limit);
        if (trades.size() < limit) {
            facts.put("actual_count_note",
                    "实际只获取到 " + trades.size() + " 条（接口可用样本不足），非完整 " + limit + " 条");
        }
        facts.put("sample_basis", "最近N笔成交，不代表固定时间窗口；统计基于全部实际样本；"
                + "明细返回本次实际取得的全部记录");
        facts.putArray("recent_trades_columns").add("time_utc8").add("price_usdt")
                .add(contracts ? "quantity_contracts" : "quantity_" + base).add("aggressor_side");
        ArrayNode rows = facts.putArray("recent_trades");
        trades.forEach(trade -> {
            ArrayNode row = rows.addArray();
            row.add(Times.readable(trade.time()));
            row.add(trade.price());
            row.add(trade.qty());
            row.add(trade.buyAggressor() ? "buy" : "sell");
        });
        return facts;
    }
}
