package com.cexpilot.market.tool;

import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.MarkPrice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 标记价格 / 指数价格 / 基差。
 */
@Component
public class GetMarkPriceTool extends AbstractMarketTool {

    public GetMarkPriceTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_mark_price";
    }

    @Override
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        MarkPrice markPrice = market.markPrice(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        if (markPrice.markPrice() != null) {
            facts.put("mark_price", markPrice.markPrice());
        }
        if (markPrice.indexPrice() != null) {
            facts.put("index_price", markPrice.indexPrice());
        }
        if (markPrice.markPrice() != null && markPrice.indexPrice() != null) {
            facts.put("basis", markPrice.markPrice().subtract(markPrice.indexPrice()));
        }
        if (markPrice.fundingRate() != null) {
            facts.put("current_funding_rate", markPrice.fundingRate());
            facts.put("current_funding_rate_pct",
                    markPrice.fundingRate().multiply(new BigDecimal("100")).stripTrailingZeros());
        }
        if (markPrice.nextFundingTime() > 0) {
            facts.put("next_funding_time", markPrice.nextFundingTime());
        }
        return facts;
    }
}
