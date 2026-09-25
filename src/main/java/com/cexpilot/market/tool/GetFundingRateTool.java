package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.FundingInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 资金费率当前快照：当前费率及其状态、对应结算时间、下一次结算时间。
 * 历史序列走 get_funding_rate_history，区间统计走 get_funding_rate_statistics。
 * 费率以小数表示（0.0001 = 0.01%），facts 同时给出百分比形式避免 LLM 换算出错。
 * 两所口径不同：OKX 当前费率是预测值（predicted），Binance 是最近一期已结算值（settled），
 * current_rate_kind 显式标注；snapshot_time 是快照采集时间，与结算时间含义不同。
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
    protected JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        FundingInfo info = market.fundingSnapshot(exchange, base);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        if (info.currentRate() != null) {
            facts.put("current_funding_rate", MarketCalculator.roundPlain(info.currentRate(), 8));
            facts.put("current_funding_rate_pct",
                    MarketCalculator.roundPlain(info.currentRate().multiply(new BigDecimal("100")), 4));
            facts.put("current_rate_kind", info.currentRateKind());
        } else {
            facts.put("data_status", "历史费率为空，无法确定最近一期已结算费率");
        }
        if (info.currentRateSettlementTime() > 0) {
            facts.put("current_rate_settlement_time_utc8",
                    Times.readable(info.currentRateSettlementTime()));
        }
        if (info.nextFundingTime() > 0) {
            facts.put("next_funding_time_utc8", Times.readable(info.nextFundingTime()));
        }
        if (info.followingFundingTime() > 0) {
            facts.put("following_funding_time_utc8", Times.readable(info.followingFundingTime()));
        }
        if (info.snapshotTime() > 0) {
            facts.put("snapshot_time_utc8", Times.readable(info.snapshotTime()));
        }
        facts.putObject("units").put("current_funding_rate", "fraction")
                .put("current_funding_rate_pct", "%")
                .put("current_rate_settlement_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8")
                .put("next_funding_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8")
                .put("following_funding_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8")
                .put("snapshot_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8");
        return facts;
    }
}
