package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OpenInterestInfo;
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
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        OpenInterestInfo info = market.openInterest(exchange, base);

        boolean okx = exchange == com.cexpilot.market.Exchange.OKX;
        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("open_interest", MarketCalculator.round(info.currentOi(), 2));
        facts.put("unit", info.unit());
        // 单位已统一为 USD；OKX 历史是全市场合约汇总，与当前值的单合约口径不同
        facts.put("history_scope", okx
                ? "该币种全市场合约汇总；不同于当前值的单合约口径" : "该永续合约");
        facts.putArray("history_columns").add("timestamp_ms").add("open_interest");
        var changePct = MarketCalculator.oiChangePct(info.history());
        if (changePct != null) {
            facts.put("oi_change_24h_pct", changePct);
        }

        ArrayNode history = facts.putArray("history");
        for (OpenInterestInfo.OiPoint point : info.history()) {
            ArrayNode row = history.addArray();
            row.add(point.timestamp());
            row.add(MarketCalculator.round(point.oi(), 2));
        }
        return facts;
    }
}
