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
        return "获取最新成交价、24小时涨跌幅（百分比）、24小时成交量（以基础币计）与成交额（USDT），用于回答「现在价格多少 / 今天涨了多少 / 成交量多大」这类问题";
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
        facts.put("volume_24h_base", ticker.baseVolume24h());
        facts.put("turnover_24h_usdt", ticker.quoteVolume24h());
        // 量的单位二义性是 LLM 误标的源头（把 BTC 个数当成 USDT 成交额），显式标注
        ObjectNode units = facts.putObject("units");
        units.put("volume_24h_base", base);
        units.put("turnover_24h_usdt", "USDT");
        return facts;
    }
}
