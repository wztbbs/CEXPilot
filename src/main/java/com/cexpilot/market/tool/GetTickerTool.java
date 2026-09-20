package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.Ticker;
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
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        Ticker ticker = market.ticker(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("last_price", ticker.lastPrice());
        facts.put("change_pct_24h", ticker.changePct24h());
        facts.put("volume_24h_base", MarketCalculator.round(ticker.baseVolume24h(), 4));
        facts.put("turnover_24h_usdt", MarketCalculator.round(ticker.quoteVolume24h(), 2));
        // 量的单位二义性是 LLM 误标的源头（把 BTC 个数当成 USDT 成交额），显式标注
        ObjectNode units = facts.putObject("units");
        units.put("volume_24h_base", base);
        units.put("turnover_24h_usdt", "USDT");
        return facts;
    }
}
