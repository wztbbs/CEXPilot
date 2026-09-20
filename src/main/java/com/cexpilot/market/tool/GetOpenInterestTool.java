package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OpenInterestInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 持仓量：当前值 + 24 小时历史序列 + 已计算的变化百分比。
 * 当前值恒为单合约口径（open_interest_single_contract）；历史序列在 OKX 是该币种
 * 全市场合约汇总、在币安是同合约——两个字段口径可能不同，字段名显式拆开，
 * 变化率 series_change_24h_pct 由序列自身算出，禁止模型跨口径相减。
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
        facts.put("open_interest_single_contract", MarketCalculator.round(info.currentOi(), 2));
        facts.put("unit", info.unit());
        facts.put("series_scope", okx
                ? "该币种全市场合约汇总；与 open_interest_single_contract 口径不同，禁止跨口径相减"
                : "该永续合约，与 open_interest_single_contract 同口径");
        facts.putArray("series_columns").add("timestamp_ms").add("open_interest");
        var changePct = MarketCalculator.oiChangePct(info.history());
        if (changePct != null) {
            facts.put("series_change_24h_pct", changePct);
        }

        ArrayNode series = facts.putArray("open_interest_aggregate_series");
        for (OpenInterestInfo.OiPoint point : info.history()) {
            ArrayNode row = series.addArray();
            row.add(point.timestamp());
            row.add(MarketCalculator.round(point.oi(), 2));
        }
        return facts;
    }
}
