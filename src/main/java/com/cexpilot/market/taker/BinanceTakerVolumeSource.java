package com.cexpilot.market.taker;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 币安 taker 成交量统计数据源：futures/data/takerlongshortRatio（5m 周期，
 * buyVol/sellVol 为基础币量）。参数映射（BTC → BTCUSDT）、endTime 游标分页、
 * 按 timestamp 去重排序。
 * 注意币安只保留最近约 30 天（capability.retentionDays=30，policy 前置拦截）。
 * 截断方向沿用同族 openInterestHist 的实测结论（窗口内返回靠 endTime 的最新 N 条），
 * 本接口未单独实测；若假设不成立，覆盖核对会判 missing 并抑制统计输出（安全失败）。
 */
@Component
public class BinanceTakerVolumeSource implements TakerVolumeSource {

    private static final int PAGE_LIMIT = 500;
    private static final int MAX_PAGES = 20;
    private static final int RETENTION_DAYS = 30;

    /** 单页拉取，便于测试注入 fake 页。 */
    @FunctionalInterface
    interface PageFetcher {
        List<TakerVolumePoint> page(String symbol, long startTimeMs, long endTimeMs, int limit);
    }

    private final PageFetcher fetcher;

    @Autowired
    public BinanceTakerVolumeSource(BinanceClient client) {
        this((symbol, startTimeMs, endTimeMs, limit) ->
                client.takerLongShortRatio(symbol, INTERVAL.code(), startTimeMs, endTimeMs, limit));
    }

    BinanceTakerVolumeSource(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(INTERVAL), PAGE_LIMIT, MAX_PAGES, RETENTION_DAYS);
    }

    @Override
    public FetchResult fetch(String base, long startMs, long endMs) {
        String symbol = SymbolMapper.binanceSymbol(base);
        Map<Long, TakerVolumePoint> byTime = new TreeMap<>();
        String abortReason = null;
        // futures/data 系列接口在指定窗口内返回靠 endTime 的最新 N 条（同 openInterestHist
        // 实测行为），因此从区间右端向左翻页：endTime 游标 = 本页最旧 ts - 1
        long cursorEnd = endMs - 1;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TakerVolumePoint> points = fetcher.page(symbol, startMs, cursorEnd, PAGE_LIMIT);
            if (points.isEmpty()) {
                break;
            }
            long oldestTime = Long.MAX_VALUE;
            for (TakerVolumePoint point : points) {
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
