package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
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
        if (ticker.changePct24h() != null) {
            facts.put("change_pct_24h", ticker.changePct24h());
        } else {
            facts.putNull("change_pct_24h");
            facts.put("change_pct_24h_note", "24 小时前基准价（open24h）为 0，涨跌幅无法计算");
        }
        facts.put("volume_24h_base", MarketCalculator.round(ticker.baseVolume24h(), 4));
        facts.put("turnover_24h_usdt", MarketCalculator.round(ticker.quoteVolume24h(), 2));
        if (ticker.quoteVolumeEstimated()) {
            facts.put("turnover_24h_usdt_estimated", true);
        }
        if (ticker.timestamp() > 0) {
            facts.put("as_of_utc8", Times.readable(ticker.timestamp()));
        }
        // 量的单位二义性是 LLM 误标的源头（把 BTC 个数当成 USDT 成交额），显式标注
        ObjectNode units = facts.putObject("units");
        units.put("volume_24h_base", base);
        units.put("turnover_24h_usdt", ticker.quoteVolumeEstimated()
                ? "USDT（估算：24h 基础币成交量 × 最新价，非逐笔成交额汇总）" : "USDT");
        return facts;
    }
}
