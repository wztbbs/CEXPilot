package com.cexpilot.market.tool;

import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.runtime.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class GetTickerTool extends AbstractMarketTool {

    public GetTickerTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_ticker";
    }

    @Override
    public String description() {
        return "获取最新成交价、24小时涨跌幅（百分比）与24小时成交量，用于回答「现在价格多少 / 今天涨了多少」这类问题";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.parse("""
                {"type": "object", "properties": {%s}, "required": ["exchange", "symbol"]}
                """.formatted(exchangeSymbolSchema()));
    }

    @Override
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        Ticker ticker = market.ticker(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("last_price", ticker.lastPrice());
        facts.put("change_pct_24h", ticker.changePct24h());
        facts.put("base_volume_24h", ticker.baseVolume24h());
        facts.put("quote_volume_24h_usdt", ticker.quoteVolume24h());
        return facts;
    }
}
