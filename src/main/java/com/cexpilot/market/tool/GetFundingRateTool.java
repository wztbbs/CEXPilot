package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.FundingInfo;
import com.cexpilot.runtime.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 资金费率：当前值 + 历史序列 + 已计算的趋势。
 * 费率以小数表示（0.0001 = 0.01%），facts 同时给出百分比形式避免 LLM 换算出错。
 */
@Component
public class GetFundingRateTool extends AbstractMarketTool {

    public GetFundingRateTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_funding_rate";
    }

    @Override
    public String description() {
        return "获取永续合约资金费率：当前费率、下次结算时间、最近历史费率序列与已计算的趋势（rising/falling/flat）。费率为正说明多头付费给空头，为负则相反";
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
        FundingInfo info = market.funding(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("current_funding_rate", info.currentRate());
        if (info.currentRate() != null) {
            facts.put("current_funding_rate_pct",
                    info.currentRate().multiply(new BigDecimal("100")).stripTrailingZeros());
        }
        facts.put("next_funding_time", info.nextFundingTime());
        facts.put("trend", MarketCalculator.fundingTrend(info.recentRates()));

        ArrayNode rates = facts.putArray("recent_rates");
        for (BigDecimal rate : info.recentRates()) {
            rates.add(rate);
        }
        return facts;
    }
}
