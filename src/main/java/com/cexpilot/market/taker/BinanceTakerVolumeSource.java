package com.cexpilot.market.taker;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 币安 taker 成交量统计数据源：futures/data/takerlongshortRatio（5m 周期，
 * buyVol/sellVol 为基础币量）。参数映射（BTC → BTCUSDT）、endTime 游标分页、
 * 两端多取一个周期，分页保留重叠，按 timestamp 去重排序；业务范围由查询服务过滤。
 * 注意币安只保留最近约 30 天（capability.retentionDays=30，policy 前置拦截）。
 * 经代理实测，指定窗口及较小 limit 时返回靠 endTime 的最新 N 条，因此向过去翻页。
 * 不依赖接口直接返回精确业务范围，完整性仍由查询服务逐点核对。
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
    public FetchResult fetch(String base, long startMs, long endMs, Instant requestTime) {
        TakerFetchWindow window = TakerFetchWindow.expand(startMs, endMs, requestTime, RETENTION_DAYS);
        if (window.start() >= window.end()) {
            return new FetchResult(List.of(), null);
        }
        String symbol = SymbolMapper.binanceSymbol(base);
        Map<Long, TakerVolumePoint> byTime = new TreeMap<>();
        String abortReason = null;
        // 不对 endTime 减 1ms；接口边界可能按周期截断。分页末端保留一个周期重叠。
        long intervalMs = INTERVAL.duration().toMillis();
        long cursorEnd = window.end();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TakerVolumePoint> points = fetcher.page(symbol, window.start(), cursorEnd, PAGE_LIMIT);
            if (points.isEmpty()) {
                break;
            }
            long oldestTime = Long.MAX_VALUE;
            for (TakerVolumePoint point : points) {
                byTime.putIfAbsent(point.timestamp(), point);
                oldestTime = Math.min(oldestTime, point.timestamp());
            }
            if (points.size() < PAGE_LIMIT) {
                break; // 本页未满：窗口内已没有更早的数据
            }
            if (oldestTime <= startMs) {
                break; // 已跨过计算范围左端，无需为补齐外扩区域继续翻页
            }
            long nextEnd = oldestTime + intervalMs;
            if (nextEnd >= cursorEnd) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            cursorEnd = nextEnd;
            if (page == MAX_PAGES - 1) {
                abortReason = "分页请求预算用尽（" + MAX_PAGES + " 页），中止拉取";
            }
        }
        return new FetchResult(new ArrayList<>(byTime.values()), abortReason);
    }
}
