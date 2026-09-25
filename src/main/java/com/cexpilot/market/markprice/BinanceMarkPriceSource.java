package com.cexpilot.market.markprice;

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
 * 币安标记/指数价格 K 线数据源：priceType 选择 markPriceKlines（symbol）或
 * indexPriceKlines（pair），startTime 游标正向翻页，volume 列无意义置 null。
 */
@Component
public class BinanceMarkPriceSource implements MarkPriceSource {

    private static final int PAGE_LIMIT = 1500;
    private static final int MAX_PAGES = 4;

    /** 单页拉取，便于测试注入 fake 页；priceType 由 source 转换为具体接口。 */
    @FunctionalInterface
    interface PageFetcher {
        List<Candle> page(PriceType priceType, String instrument, String interval,
                          long startTimeMs, long endTimeMs, int limit);
    }

    private final PageFetcher fetcher;

    @Autowired
    public BinanceMarkPriceSource(BinanceClient client) {
        this((priceType, instrument, interval, startMs, endMs, limit) -> priceType == PriceType.INDEX
                ? client.indexPriceKlines(instrument, interval, startMs, endMs, limit)
                : client.markPriceKlines(instrument, interval, startMs, endMs, limit));
    }

    BinanceMarkPriceSource(PageFetcher fetcher) {
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
    public FetchResult fetch(String base, PriceType priceType, CandleInterval interval,
                             long startMs, long endMs) {
        // mark 与 index 的标识字段相同（BTCUSDT），只是接口参数名不同（symbol / pair）
        String instrument = SymbolMapper.binanceSymbol(base);
        long intervalMs = interval.duration().toMillis();
        Map<Long, Candle> byOpenTime = new LinkedHashMap<>();
        String abortReason = null;
        long cursor = startMs;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Candle> candles = fetcher.page(priceType, instrument, interval.code(), cursor, endMs - 1, PAGE_LIMIT);
            if (candles.isEmpty()) {
                break;
            }
            long lastOpenTime = Long.MIN_VALUE;
            for (Candle candle : candles) {
                if (candle.openTime() >= startMs && candle.openTime() < endMs) {
                    byOpenTime.putIfAbsent(candle.openTime(), stripVolume(candle));
                }
                lastOpenTime = Math.max(lastOpenTime, candle.openTime());
            }
            if (candles.size() < PAGE_LIMIT) {
                break;
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

    /** 标记/指数价格 K 线的 volume 列无意义（接口返回 "0"），统一置 null。 */
    private static Candle stripVolume(Candle candle) {
        return new Candle(candle.openTime(), candle.open(), candle.high(), candle.low(),
                candle.close(), null, null, candle.confirmed());
    }
}
