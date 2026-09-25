package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.MarkPrice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 标记价格 / 指数价格 / 基差。
 * 价格保留上游原始精度，不做固定位数截断（小市值币种价格可能远小于 0.0001）。
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
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
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
        if (markPrice.markPriceTime() > 0) {
            facts.put("mark_price_time_utc8", Times.readable(markPrice.markPriceTime()));
        }
        if (markPrice.indexPriceTime() > 0) {
            facts.put("index_price_time_utc8", Times.readable(markPrice.indexPriceTime()));
        }
        if (markPrice.markPriceTime() > 0 && markPrice.indexPriceTime() > 0) {
            facts.put("basis_time_diff_ms",
                    Math.abs(markPrice.markPriceTime() - markPrice.indexPriceTime()));
        }
        if (markPrice.fundingRate() != null) {
            facts.put("current_funding_rate", markPrice.fundingRate());
            // stripTrailingZeros 后转 plain，避免 Jackson 按 toString 序列化出科学计数
            facts.put("current_funding_rate_pct", new BigDecimal(markPrice.fundingRate()
                    .multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()));
            // Binance premiumIndex 的 lastFundingRate 是快照接口报告的费率，与历史接口的
            // 最近已结算值可能存在滞后差异（实测两者会不一致），不能标为 settled；
            // 已结算费率以 get_funding_rate（历史末条）为准
            if (exchange == Exchange.BINANCE) {
                facts.put("current_funding_rate_kind", "snapshot_reported");
                if (markPrice.markPriceTime() > 0) {
                    facts.put("current_funding_rate_as_of_utc8",
                            Times.readable(markPrice.markPriceTime()));
                }
            }
        }
        if (markPrice.nextFundingTime() > 0) {
            facts.put("next_funding_time_utc8", Times.readable(markPrice.nextFundingTime()));
        }
        return facts;
    }
}
