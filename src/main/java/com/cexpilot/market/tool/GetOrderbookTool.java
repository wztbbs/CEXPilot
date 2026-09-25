package com.cexpilot.market.tool;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.OrderBook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 盘口快照 + 已计算的不平衡度与价差。
 * 注意：盘口是当前快照，不能用于解释过去的价格变化。
 * OKX 的档位数量是合约张数，quantity_unit / 列名显式标注，不按合约规格换算。
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
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        int requestedDepth = args.path("depth").intValue();
        OrderBook book = market.orderBook(exchange, base, requestedDepth);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        if (exchange == Exchange.BINANCE) {
            int actualDepth = BinanceClient.mapDepth(requestedDepth);
            facts.put("depth", actualDepth);
            if (actualDepth != requestedDepth) {
                facts.put("requested_depth", requestedDepth);
            }
        }
        if (book.timestamp() > 0) {
            facts.put("as_of_utc8", Times.readable(book.timestamp()));
        }
        String qtyUnit = "contracts".equals(book.qtyUnit()) ? "contracts" : base;
        facts.put("quantity_unit", "contracts".equals(book.qtyUnit())
                ? "contracts（合约张数，未换算为币数）" : base + "（基础币）");
        if (!book.bids().isEmpty() && !book.asks().isEmpty()) {
            facts.put("best_bid", book.bids().get(0).price());
            facts.put("best_ask", book.asks().get(0).price());
        }
        int imbalanceLevels = Math.min(OUTPUT_LEVELS, Math.min(book.bids().size(), book.asks().size()));
        var imbalance = MarketCalculator.orderbookImbalance(book, imbalanceLevels);
        if (imbalance != null) {
            facts.put("imbalance_top" + imbalanceLevels, imbalance);
        }
        facts.put("bid_levels", book.bids().size());
        facts.put("ask_levels", book.asks().size());
        var spread = MarketCalculator.spread(book);
        if (spread != null) {
            facts.put("spread", spread);
        }
        String qtyColumn = "quantity_" + qtyUnit;
        facts.putArray("bids_columns").add("price_usdt").add(qtyColumn);
        facts.putArray("asks_columns").add("price_usdt").add(qtyColumn);
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
