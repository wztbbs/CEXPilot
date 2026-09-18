package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OpenInterestInfo;
import com.cexpilot.runtime.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 持仓量：当前值 + 24 小时历史 + 已计算的变化百分比。
 */
@Component
public class GetOpenInterestTool extends AbstractMarketTool {

    public GetOpenInterestTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_open_interest";
    }

    @Override
    public String description() {
        return "获取永续合约持仓量（Open Interest）：当前持仓量（以基础币计）、近24小时历史序列与已计算的24小时变化百分比";
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
        OpenInterestInfo info = market.openInterest(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("open_interest", info.currentOi());
        facts.put("unit", info.unit());
        var changePct = MarketCalculator.oiChangePct(info.history());
        if (changePct != null) {
            facts.put("oi_change_24h_pct", changePct);
        }

        ArrayNode history = facts.putArray("history");
        for (OpenInterestInfo.OiPoint point : info.history()) {
            ArrayNode row = history.addArray();
            row.add(point.timestamp());
            row.add(point.oi());
        }
        return facts;
    }
}
