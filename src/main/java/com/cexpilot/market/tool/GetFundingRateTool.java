package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.FundingInfo;
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
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        FundingInfo info = market.funding(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("current_funding_rate", MarketCalculator.roundPlain(info.currentRate(), 8));
        if (info.currentRate() != null) {
            facts.put("current_funding_rate_pct",
                    MarketCalculator.roundPlain(info.currentRate().multiply(new BigDecimal("100")), 4));
        }
        if (info.nextFundingTime() > 0) {
            facts.put("next_funding_time_utc8", Times.readable(info.nextFundingTime()));
        }
        facts.put("trend", MarketCalculator.fundingTrend(info.recentRates()));
        facts.putObject("units").put("current_funding_rate", "fraction")
                .put("current_funding_rate_pct", "%").put("recent_rates", "fraction")
                .put("next_funding_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8");

        ArrayNode rates = facts.putArray("recent_rates");
        for (BigDecimal rate : info.recentRates()) {
            rates.add(MarketCalculator.roundPlain(rate, 8));
        }
        return facts;
    }
}
