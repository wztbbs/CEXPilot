package com.cexpilot.market.tool;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.TimeWindow;
import com.cexpilot.market.Times;
import com.cexpilot.market.model.Candle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * K线 + 窗口内价格变化的确定性计算结果。
 * 返回 Structured Facts（含已算好的涨跌幅），candles 以压缩数组给出。
 */
@Component
public class GetKlinesTool extends AbstractMarketTool {

    public GetKlinesTool(MarketDataService market) {
        super(market);
    }

    @Override
    public String name() {
        return "get_klines";
    }

    @Override
    protected JsonNode doExecute(JsonNode args) {
        var exchange = parseExchange(args);
        String base = parseBase(args);
        TimeWindow window = parseWindow(args);

        List<Candle> candles = market.klines(exchange, base, window);
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("未获取到K线数据");
        }
        MarketCalculator.PriceChange change = MarketCalculator.priceChange(candles);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("exchange", exchange.displayName());
        facts.put("symbol", base);
        facts.put("window", window.code());
        facts.put("candle_interval", window.interval());
        facts.put("candle_count", candles.size());

        ObjectNode priceChange = facts.putObject("price_change");
        priceChange.put("start_price", change.startPrice());
        priceChange.put("end_price", change.endPrice());
        priceChange.put("change_pct", change.changePct());
        priceChange.put("high", change.high());
        priceChange.put("low", change.low());

        facts.set("freshness", freshness(candles, window));
        facts.putArray("candles_columns").add("open_time_utc8").add("open_price_usdt")
                .add("high_price_usdt").add("low_price_usdt").add("close_price_usdt").add("volume");

        ArrayNode rows = facts.putArray("candles");
        for (Candle c : candles) {
            ArrayNode row = rows.addArray();
            row.add(Times.readable(c.openTime()));
            row.add(c.open());
            row.add(c.high());
            row.add(c.low());
            row.add(c.close());
            row.add(c.volume());
        }
        return facts;
    }

    static ObjectNode freshness(List<Candle> candles, TimeWindow window) {
        ObjectNode node = MAPPER.createObjectNode();
        long lastOpenTime = candles.get(candles.size() - 1).openTime();
        long intervalMs = switch (window.code()) {
            case "1h" -> 5 * 60_000L;
            case "4h" -> 15 * 60_000L;
            default -> 3_600_000L;
        };
        long ageMs = System.currentTimeMillis() - lastOpenTime;
        node.put("last_candle_open_time_utc8", Times.readable(lastOpenTime));
        node.put("age_seconds", ageMs / 1000);
        node.put("data_fresh", ageMs <= intervalMs * 2);
        return node;
    }
}
