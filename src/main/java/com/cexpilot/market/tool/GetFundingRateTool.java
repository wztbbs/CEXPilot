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
import java.util.List;

/**
 * 资金费率：当前值 + 历史序列 + 已计算的趋势。
 * 费率以小数表示（0.0001 = 0.01%），facts 同时给出百分比形式避免 LLM 换算出错。
 * 两所口径不同：OKX 当前费率是预测值、历史是实际结算值（realizedRate）；
 * Binance 当前费率取历史末条已结算值（费率与结算时间同源）。
 * current_rate_kind / recent_rates_kind 显式标注；snapshot_time 是快照采集时间，
 * 与结算时间含义不同。next_funding_time 一律表示下一次结算；OKX 另有 following_funding_time
 * 表示再下一期（Binance 不提供该字段）。
 */
@Component
public class GetFundingRateTool extends AbstractMarketTool {

    private static final int OUTPUT_RATES = 20;

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
        int count = args.path("count").asInt(10);
        boolean details = args.path("details").asBoolean(false);
        FundingInfo info = market.funding(exchange, base, count);

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
        if (info.snapshotTime() > 0) {
            facts.put("snapshot_time_utc8", Times.readable(info.snapshotTime()));
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
        List<BigDecimal> rates = info.recentRates().stream()
                .map(FundingInfo.RatePoint::rate).toList();
        facts.put("requested_count", count);
        facts.put("actual_count", rates.size());
        if (rates.size() < count) {
            facts.put("actual_count_note",
                    "实际只获取到 " + rates.size() + " 期（最近期未结算跳过或合约较新），非完整 " + count + " 期");
        }
        facts.put("trend", MarketCalculator.fundingTrend(rates));
        facts.put("trend_method", "按实际取得的" + rates.size()
                + "期后半段均值 vs 前半段均值，差值小于 0.005% 判为 flat；不足 4 期为 unknown；未按结算周期归一化");
        facts.put("recent_rates_kind", info.recentRatesKind());
        facts.putObject("units").put("current_funding_rate", "fraction")
                .put("current_funding_rate_pct", "%").put("recent_rates", "fraction")
                .put("current_rate_settlement_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8")
                .put("next_funding_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8")
                .put("following_funding_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8")
                .put("snapshot_time_utc8", "yyyy-MM-dd HH:mm:ss UTC+8");

        facts.putArray("recent_rates_columns").add("settle_time_utc8").add("rate");
        List<FundingInfo.RatePoint> points = info.recentRates();
        // 明细窗口：请求 ≤20 期或 details=true 时输出全部，否则只输出最新 20 期；统计与趋势仍基于全部样本
        int from = (count <= OUTPUT_RATES || details) ? 0 : Math.max(0, points.size() - OUTPUT_RATES);
        if (from > 0) {
            facts.put("recent_rates_note", "明细仅输出最新 " + OUTPUT_RATES + " 期（共 " + points.size()
                    + " 期）；设 details=true 可输出全部");
        }
        ArrayNode recentRates = facts.putArray("recent_rates");
        for (FundingInfo.RatePoint point : points.subList(from, points.size())) {
            ArrayNode row = recentRates.addArray();
            row.add(point.fundingTime() > 0 ? Times.readable(point.fundingTime()) : "unknown");
            row.add(MarketCalculator.roundPlain(point.rate(), 8));
        }
        return facts;
    }
}
