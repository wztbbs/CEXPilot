package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OrderBook;
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
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        int depth = args.path("depth").intValue();
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
        facts.putArray("bids_columns").add("price_usdt").add("quantity");
        facts.putArray("asks_columns").add("price_usdt").add("quantity");
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
