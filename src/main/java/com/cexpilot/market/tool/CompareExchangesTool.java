package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.TimeWindow;
import com.cexpilot.market.model.Candle;
import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 跨交易所对比（两所价格差异的异常检测）。
 * 两所并行拉取，一所失败时返回另一所的部分结果并注明（Partial Result），
 * 两所都失败才返回 failure。
 */
@Component
public class CompareExchangesTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal DIVERGENCE_THRESHOLD_PCT = new BigDecimal("0.3");

    private final MarketDataService market;

    public CompareExchangesTool(MarketDataService market) {
        this.market = market;
    }

    @Override
    public String name() {
        return "compare_exchanges";
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            String base = SymbolMapper.normalize(args.path("symbol").asText(null));
            TimeWindow window = TimeWindow.parse(args.path("window").asText());

            CompletableFuture<ExchangeResult> binanceFuture = CompletableFuture.supplyAsync(
                    () -> fetch(Exchange.BINANCE, base, window));
            CompletableFuture<ExchangeResult> okxFuture = CompletableFuture.supplyAsync(
                    () -> fetch(Exchange.OKX, base, window));
            CompletableFuture.allOf(binanceFuture, okxFuture).join();
            ExchangeResult binance = binanceFuture.join();
            ExchangeResult okx = okxFuture.join();

            if (binance.error != null && okx.error != null) {
                return ToolResult.failure("两个交易所都获取失败: binance=" + binance.error + "; okx=" + okx.error);
            }

            ObjectNode facts = MAPPER.createObjectNode();
            facts.put("symbol", base);
            facts.put("window", window.code());
            facts.set("binance", toJson(binance));
            facts.set("okx", toJson(okx));

            if (binance.change != null && okx.change != null) {
                MarketCalculator.Divergence divergence = MarketCalculator.divergence(
                        binance.change, okx.change, DIVERGENCE_THRESHOLD_PCT);
                ObjectNode div = facts.putObject("divergence");
                // diff 是两所涨跌幅（%）数值之差，单位为百分点
                div.put("unit", "percentage_points");
                div.put("diff_percentage_points", divergence.diffPct());
                div.put("threshold_percentage_points", DIVERGENCE_THRESHOLD_PCT);
                div.put("significant", divergence.significant());
                div.put("significant_meaning", "diff 超过固定阈值 0.3 个百分点即为 true，是业务阈值命中，不是统计显著性检验");
            }
            return ToolResult.success(facts);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("参数错误: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("工具内部异常: " + e.getMessage());
        }
    }

    private ExchangeResult fetch(Exchange exchange, String base, TimeWindow window) {
        try {
            List<Candle> candles = market.klines(exchange, base, window);
            return new ExchangeResult(MarketCalculator.priceChange(candles), null);
        } catch (Exception e) {
            return new ExchangeResult(null, e.getMessage());
        }
    }

    private static ObjectNode toJson(ExchangeResult result) {
        ObjectNode node = MAPPER.createObjectNode();
        if (result.error != null) {
            node.put("error", result.error);
            return node;
        }
        if (result.change == null) {
            // 接口成功但窗口内无 K 线：该侧数据不足，另一侧结果仍保留
            node.put("data_status", "数据不足：窗口内没有 K 线");
            return node;
        }
        node.put("start_price", result.change.startPrice());
        node.put("end_price", result.change.endPrice());
        node.put("change_pct", result.change.changePct());
        return node;
    }

    private record ExchangeResult(MarketCalculator.PriceChange change, String error) {
    }
}
