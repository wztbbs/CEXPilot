package com.cexpilot.market.kline;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.OkxClient;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.CandleInterval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OKX K 线数据源：参数映射（BTC → BTC-USDT-SWAP，粒度 code → bar）、
 * after 游标从区间右端向过去翻页、返回数据按 openTime 去重排序。
 *
 * 近期与历史接口的选择规则：/market/candles 只覆盖最近 {@value #RECENT_BARS} 根，
 * 更早的部分必须走 /market/history-candles（单页上限也更小）。区间跨越分界时
 * 拆成两段分别拉取，缝合处的完整性由 KlineCoverageValidator 按预期序列核对。
 */
@Component
public class OkxKlineSource implements KlineSource {

    /** /market/candles 的覆盖范围：最近 1,440 根。 */
    static final int RECENT_BARS = 1440;
    private static final int RECENT_PAGE_LIMIT = 300;
    private static final int HISTORY_PAGE_LIMIT = 100;

    /** 单页拉取，便于测试注入 fake 页；history=true 时打历史接口。 */
    @FunctionalInterface
    interface PageFetcher {
        List<Candle> page(String instId, String bar, long beforeTs, long afterTs, int limit, boolean history);
    }

    private final PageFetcher fetcher;
    private final Clock clock;

    @Autowired
    public OkxKlineSource(OkxClient client, Clock clock) {
        this((instId, bar, before, after, limit, history) -> history
                ? client.historyCandles(instId, bar, before, after, limit)
                : client.candles(instId, bar, before, after, limit), clock);
    }

    OkxKlineSource(PageFetcher fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(CandleInterval.values()), 300, 20);
    }

    /** OKX 粒度编码：5m/15m 与 CandleInterval 一致，1h 为 1H。 */
    static String bar(CandleInterval interval) {
        return switch (interval) {
            case FIVE_MINUTES -> "5m";
            case FIFTEEN_MINUTES -> "15m";
            case ONE_HOUR -> "1H";
        };
    }

    @Override
    public FetchResult fetch(KlineQueryRequest effective) {
        String instId = SymbolMapper.okxInstId(effective.base());
        String bar = bar(effective.interval());
        long intervalMs = effective.interval().duration().toMillis();
        long startMs = effective.range().startInclusive().toEpochMilli();
        long endMs = effective.range().endExclusive().toEpochMilli();

        long cutoff = clock.instant().toEpochMilli() - RECENT_BARS * intervalMs;
        Map<Long, Candle> byOpenTime = new TreeMap<>();
        List<String> abortReasons = new ArrayList<>();

        if (startMs < cutoff) {
            FetchResult r = paginate(instId, bar, startMs, Math.min(endMs, cutoff),
                    HISTORY_PAGE_LIMIT, true, intervalMs);
            r.candles().forEach(c -> byOpenTime.putIfAbsent(c.openTime(), c));
            if (r.abortReason() != null) {
                abortReasons.add("历史段: " + r.abortReason());
            }
        }
        if (endMs > Math.max(startMs, cutoff)) {
            FetchResult r = paginate(instId, bar, Math.max(startMs, cutoff), endMs,
                    RECENT_PAGE_LIMIT, false, intervalMs);
            r.candles().forEach(c -> byOpenTime.putIfAbsent(c.openTime(), c));
            if (r.abortReason() != null) {
                abortReasons.add("近期段: " + r.abortReason());
            }
        }
        return new FetchResult(new ArrayList<>(byOpenTime.values()),
                abortReasons.isEmpty() ? null : String.join("；", abortReasons));
    }

    /** 单段拉取 [segStartMs, segEndMs)：after 游标从右端向过去翻页。 */
    private FetchResult paginate(String instId, String bar, long segStartMs, long segEndMs,
                                 int pageLimit, boolean history, long intervalMs) {
        Map<Long, Candle> byOpenTime = new TreeMap<>();
        String abortReason = null;
        long maxPages = Math.max(1, (segEndMs - segStartMs) / intervalMs / pageLimit + 1);
        // before=segStartMs-1：包含 segStartMs 本身（OKX before 为“比该 ts 更新”，不含等值）
        long before = segStartMs - 1;
        long after = segEndMs;
        for (int page = 0; page < maxPages; page++) {
            List<Candle> candles = fetcher.page(instId, bar, before, after, pageLimit, history);
            if (candles.isEmpty()) {
                break;
            }
            long oldestOpenTime = Long.MAX_VALUE;
            for (Candle candle : candles) {
                if (candle.openTime() >= segStartMs && candle.openTime() < segEndMs) {
                    byOpenTime.putIfAbsent(candle.openTime(), candle);
                }
                oldestOpenTime = Math.min(oldestOpenTime, candle.openTime());
            }
            if (candles.size() < pageLimit) {
                break; // 本页未满：交易所已没有更多数据
            }
            if (oldestOpenTime >= after) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            if (oldestOpenTime <= segStartMs) {
                break; // 已触及段左端
            }
            after = oldestOpenTime;
            if (page == maxPages - 1) {
                abortReason = "分页请求预算用尽（" + maxPages + " 页），中止拉取";
            }
        }
        return new FetchResult(new ArrayList<>(byOpenTime.values()), abortReason);
    }
}
