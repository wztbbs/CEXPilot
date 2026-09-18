package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.runtime.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 盘口快照 + 已计算的不平衡度与价差。
 * 注意：盘口是当前快照，不能用于解释过去的价格变化。
 */
@Component
public class GetOrderbookTool extends AbstractMarketTool {

    private static final int OUTPUT_LEVELS = 10;

    public GetOrderbookTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_orderbook";
    }

    @Override
    public String description() {
        return "获取当前盘口快照：买卖各档位价格与挂单量、已计算的买卖盘不平衡度（>1买盘厚，<1卖盘厚）与买卖价差。只能反映当前状态，不能解释过去的价格变化";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.parse("""
                {"type": "object", "properties": {
                %s,
                "depth": {"type": "integer", "description": "档位数，默认 20，最大 50"}
                }, "required": ["exchange", "symbol"]}
                """.formatted(exchangeSymbolSchema()));
    }

    @Override
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        int depth = args.path("depth").asInt(20);
        OrderBook book = market.orderBook(exchange, base, depth);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        if (!book.bids().isEmpty() && !book.asks().isEmpty()) {
            facts.put("best_bid", book.bids().get(0).price());
            facts.put("best_ask", book.asks().get(0).price());
        }
        var imbalance = MarketCalculator.orderbookImbalance(book, OUTPUT_LEVELS);
        if (imbalance != null) {
            facts.put("imbalance_top" + OUTPUT_LEVELS, imbalance);
        }
        var spread = MarketCalculator.spread(book);
        if (spread != null) {
            facts.put("spread", spread);
        }
        facts.set("bids", levels(book.bids()));
        facts.set("asks", levels(book.asks()));
        return facts;
    }

    private static ArrayNode levels(java.util.List<OrderBook.Level> levels) {
        ArrayNode arr = MAPPER.createArrayNode();
        levels.stream().limit(OUTPUT_LEVELS).forEach(level -> {
            ArrayNode row = arr.addArray();
            row.add(level.price());
            row.add(level.qty());
        });
        return arr;
    }
}
