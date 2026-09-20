package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.OpenInterestInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 持仓量：当前值 + 24 小时历史序列 + 已计算的变化百分比。
 * 当前值直接取序列最新点（1 小时粒度），与历史、变化率保持同一口径——
 * 不输出单合约实时值，避免 payload 里出现两个口径的数让模型挑错/跨口径相减。
 * （OKX 序列是该币种全市场合约汇总，币安是该永续合约；序列尾比实时值最多旧 1 小时。）
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
        var history = info.history();
        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        // 当前值 = 序列最新点；序列为空时兜底用实时值
        var current = history.isEmpty() ? info.currentOi() : history.get(history.size() - 1).oi();
        facts.put("open_interest", MarketCalculator.round(current, 2));
        facts.put("unit", info.unit());
        facts.put("series_scope", okx ? "该币种全市场合约汇总（1 小时粒度）" : "该永续合约（1 小时粒度）");
        facts.putArray("open_interest_series_columns").add("time_utc8").add("open_interest");
        var changePct = MarketCalculator.oiChangePct(history);
        if (changePct != null) {
            facts.put("oi_change_24h_pct", changePct);
        }

        ArrayNode series = facts.putArray("open_interest_series");
        for (OpenInterestInfo.OiPoint point : history) {
            ArrayNode row = series.addArray();
            row.add(Times.readable(point.timestamp()));
            row.add(MarketCalculator.round(point.oi(), 2));
        }
        return facts;
    }
}
