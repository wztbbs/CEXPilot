package com.cexpilot.market.tool;

import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.OpenInterestInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 持仓量当前快照：指定 USDT 永续合约的当前持仓数量，明确单位和数据时间。
 * 历史序列走 get_open_interest_history，区间统计走 get_open_interest_statistics。
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
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        OpenInterestInfo info = market.oiSnapshot(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("instrument", exchange == com.cexpilot.market.Exchange.OKX
                ? SymbolMapper.okxInstId(base) : SymbolMapper.binanceSymbol(base));
        facts.put("market_type", "USDT 本位永续合约");
        facts.put("metric", "open_interest_quantity");
        if (info.oi() != null) {
            facts.put("open_interest", info.oi());
        } else {
            facts.put("data_status", "无法确定当前持仓量");
        }
        facts.put("unit", info.unit());
        if (info.dataTime() > 0) {
            facts.put("data_time_utc8", Times.readable(info.dataTime()));
        }
        if (info.snapshotTime() > 0) {
            facts.put("snapshot_time_utc8", Times.readable(info.snapshotTime()));
        }
        return facts;
    }
}
