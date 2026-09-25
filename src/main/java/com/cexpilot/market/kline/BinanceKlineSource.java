package com.cexpilot.market.kline;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.CandleInterval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 币安 K 线数据源：参数映射（BTC → BTCUSDT，粒度 code 与币安一致）、
 * startTime 游标分页、返回数据按 openTime 去重排序。
 */
@Component
public class BinanceKlineSource implements KlineSource {

    private static final int PAGE_LIMIT = 1500;
    private static final int MAX_PAGES = 4;

    /** 单页拉取，便于测试注入 fake 页。 */
    @FunctionalInterface
    interface PageFetcher {
        List<Candle> page(String symbol, String interval, long startTimeMs, long endTimeMs, int limit);
    }

    private final PageFetcher fetcher;

    @Autowired
    public BinanceKlineSource(BinanceClient client) {
        this(client::klines);
    }

    BinanceKlineSource(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(CandleInterval.values()), PAGE_LIMIT, MAX_PAGES);
    }

    @Override
    public FetchResult fetch(KlineQueryRequest effective) {
        String symbol = SymbolMapper.binanceSymbol(effective.base());
        String interval = effective.interval().code();
        long intervalMs = effective.interval().duration().toMillis();
        long startMs = effective.range().startInclusive().toEpochMilli();
        long endMs = effective.range().endExclusive().toEpochMilli();

        Map<Long, Candle> byOpenTime = new LinkedHashMap<>();
        String abortReason = null;
        long cursor = startMs;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Candle> candles = fetcher.page(symbol, interval, cursor, endMs - 1, PAGE_LIMIT);
            if (candles.isEmpty()) {
                break;
            }
            long lastOpenTime = Long.MIN_VALUE;
            for (Candle candle : candles) {
                if (candle.openTime() >= startMs && candle.openTime() < endMs) {
                    byOpenTime.putIfAbsent(candle.openTime(), candle);
                }
                lastOpenTime = Math.max(lastOpenTime, candle.openTime());
            }
            if (candles.size() < PAGE_LIMIT) {
                break; // 本页未满：交易所已没有更多数据
            }
            long next = lastOpenTime + intervalMs;
            if (next <= cursor) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            if (next >= endMs) {
                break;
            }
            cursor = next;
            if (page == MAX_PAGES - 1) {
                abortReason = "分页请求预算用尽（" + MAX_PAGES + " 页），中止拉取";
            }
        }
        return new FetchResult(new ArrayList<>(byOpenTime.values()), abortReason);
    }
}
