package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.OpenInterestInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 两所指定 USDT 永续合约的持仓数量及近期样本；不承诺完整时间窗口。 */
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

        var history = info.history();
        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("instrument", exchange == Exchange.OKX
                ? com.cexpilot.market.SymbolMapper.okxInstId(base)
                : com.cexpilot.market.SymbolMapper.binanceSymbol(base));
        facts.put("market_type", "USDT 本位永续合约");
        facts.put("series_scope", "该永续合约（1 小时粒度）");
        facts.put("metric", "open_interest_quantity");
        facts.put("unit", info.unit());
        facts.put("sample_count", history.size());
        facts.put("window_coverage", "unverified");
        facts.putArray("open_interest_series_columns").add("time_utc8").add("open_interest");
        ArrayNode series = facts.putArray("open_interest_series");
        if (history.isEmpty()) {
            facts.put("data_status", "历史序列为空，持仓量数据不足");
            return facts;
        }
        var latest = history.get(history.size() - 1);
        facts.put("open_interest", latest.oi());
        facts.put("source", "指定合约历史序列最新采样值，非实时快照");
        facts.put("as_of_utc8", Times.readable(latest.timestamp()));
        facts.put("sample_start_utc8", Times.readable(history.get(0).timestamp()));
        // 只描述返回样本首尾的数量变化；不能将 24 个小时采样点标为 24h 变化。
        var changePct = MarketCalculator.oiChangePct(history);
        if (changePct != null) {
            facts.put("oi_change_pct", changePct);
            facts.put("change_scope", "返回样本首尾的持仓数量变化，非完整窗口统计");
        }
        for (OpenInterestInfo.OiPoint point : history) {
            series.addArray().add(Times.readable(point.timestamp())).add(point.oi());
        }
        return facts;
    }
}
