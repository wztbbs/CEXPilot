package com.cexpilot.market.oi;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.OiInterval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 币安持仓量历史数据源：参数映射（BTC → BTCUSDT，粒度 code 即 period）、
 * startTime 游标分页、按 timestamp 去重排序。
 * 注意币安只保留最近约 30 天（capability.retentionDays=30，policy 前置拦截）。
 */
@Component
public class BinanceOiSource implements OiSource {

    private static final int PAGE_LIMIT = 500;
    private static final int MAX_PAGES = 8;
    private static final int RETENTION_DAYS = 30;

    /** 单页拉取，便于测试注入 fake 页。 */
    @FunctionalInterface
    interface PageFetcher {
        List<OiPoint> page(String symbol, String period, long startTimeMs, long endTimeMs, int limit);
    }

    private final PageFetcher fetcher;

    @Autowired
    public BinanceOiSource(BinanceClient client) {
        this(client::openInterestHistory);
    }

    BinanceOiSource(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(OiInterval.values()), PAGE_LIMIT, MAX_PAGES, RETENTION_DAYS);
    }

    @Override
    public FetchResult fetch(String base, OiInterval interval, long startMs, long endMs) {
        String symbol = SymbolMapper.binanceSymbol(base);
        Map<Long, OiPoint> byTime = new TreeMap<>();
        String abortReason = null;
        // 接口在指定窗口内返回靠 endTime 的最新 N 条（实测 600 点区间 limit=500 返回最后 500 条），
        // 因此从区间右端向左翻页：endTime 游标 = 本页最旧 ts - 1
        long cursorEnd = endMs - 1;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<OiPoint> points = fetcher.page(symbol, interval.code(), startMs, cursorEnd, PAGE_LIMIT);
            if (points.isEmpty()) {
                break;
            }
            long oldestTime = Long.MAX_VALUE;
            for (OiPoint point : points) {
                if (point.timestamp() >= startMs && point.timestamp() < endMs) {
                    byTime.putIfAbsent(point.timestamp(), point);
                }
                oldestTime = Math.min(oldestTime, point.timestamp());
            }
            if (points.size() < PAGE_LIMIT) {
                break; // 本页未满：窗口内已没有更早的数据
            }
            long nextEnd = oldestTime - 1;
            if (nextEnd >= cursorEnd) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            if (oldestTime <= startMs) {
                break; // 已触及区间左端
            }
            cursorEnd = nextEnd;
            if (page == MAX_PAGES - 1) {
                abortReason = "分页请求预算用尽（" + MAX_PAGES + " 页），中止拉取";
            }
        }
        return new FetchResult(new ArrayList<>(byTime.values()), abortReason);
    }
}
