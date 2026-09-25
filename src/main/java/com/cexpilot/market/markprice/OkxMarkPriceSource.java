package com.cexpilot.market.markprice;

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
 * OKX 标记/指数价格 K 线数据源：priceType 选择 mark-price（BTC-USDT-SWAP）或
 * index（BTC-USDT）接口；近期（最近 1440 根）与历史两段拆分拉取，规则与 K 线一致。
 */
@Component
public class OkxMarkPriceSource implements MarkPriceSource {

    private static final int RECENT_BARS = 1440;
    private static final int RECENT_PAGE_LIMIT = 300;
    private static final int HISTORY_PAGE_LIMIT = 100;

    /** 单页拉取，便于测试注入 fake 页；history=true 时打历史接口。 */
    @FunctionalInterface
    interface PageFetcher {
        List<Candle> page(PriceType priceType, String instId, String bar,
                          long beforeTs, long afterTs, int limit, boolean history);
    }

    private final PageFetcher fetcher;
    private final Clock clock;

    @Autowired
    public OkxMarkPriceSource(OkxClient client, Clock clock) {
        this((priceType, instId, bar, before, after, limit, history) -> {
            if (priceType == PriceType.INDEX) {
                return history ? client.historyIndexCandles(instId, bar, before, after, limit)
                        : client.indexCandles(instId, bar, before, after, limit);
            }
            return history ? client.historyMarkPriceCandles(instId, bar, before, after, limit)
                    : client.markPriceCandles(instId, bar, before, after, limit);
        }, clock);
    }

    OkxMarkPriceSource(PageFetcher fetcher, Clock clock) {
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

    private static String instId(String base, PriceType priceType) {
        return priceType == PriceType.INDEX
                ? SymbolMapper.okxIndexInstId(base) : SymbolMapper.okxInstId(base);
    }

    /** OKX 粒度编码：与 K 线一致（5m/15m 同 code，1h 为 1H）。 */
    private static String bar(CandleInterval interval) {
        return switch (interval) {
            case FIVE_MINUTES -> "5m";
            case FIFTEEN_MINUTES -> "15m";
            case ONE_HOUR -> "1H";
        };
    }

    @Override
    public FetchResult fetch(String base, PriceType priceType, CandleInterval interval,
                             long startMs, long endMs) {
        String instId = instId(base, priceType);
        String bar = bar(interval);
        long intervalMs = interval.duration().toMillis();
        long cutoff = clock.instant().toEpochMilli() - RECENT_BARS * intervalMs;
        Map<Long, Candle> byOpenTime = new TreeMap<>();
        List<String> abortReasons = new ArrayList<>();

        if (startMs < cutoff) {
            FetchResult r = paginate(priceType, instId, bar, startMs, Math.min(endMs, cutoff),
                    HISTORY_PAGE_LIMIT, true, intervalMs);
            r.candles().forEach(c -> byOpenTime.putIfAbsent(c.openTime(), c));
            if (r.abortReason() != null) {
                abortReasons.add("历史段: " + r.abortReason());
            }
        }
        if (endMs > Math.max(startMs, cutoff)) {
            FetchResult r = paginate(priceType, instId, bar, Math.max(startMs, cutoff), endMs,
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
    private FetchResult paginate(PriceType priceType, String instId, String bar,
                                 long segStartMs, long segEndMs,
                                 int pageLimit, boolean history, long intervalMs) {
        Map<Long, Candle> byOpenTime = new TreeMap<>();
        String abortReason = null;
        long maxPages = Math.max(1, (segEndMs - segStartMs) / intervalMs / pageLimit + 1);
        // before=segStartMs-1：包含 segStartMs 本身（OKX before 为“比该 ts 更新”，不含等值）
        long before = segStartMs - 1;
        long after = segEndMs;
        for (int page = 0; page < maxPages; page++) {
            List<Candle> candles = fetcher.page(priceType, instId, bar, before, after, pageLimit, history);
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
                break;
            }
            if (oldestOpenTime >= after) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            if (oldestOpenTime <= segStartMs) {
                break;
            }
            after = oldestOpenTime;
            if (page == maxPages - 1) {
                abortReason = "分页请求预算用尽（" + maxPages + " 页），中止拉取";
            }
        }
        return new FetchResult(new ArrayList<>(byOpenTime.values()), abortReason);
    }
}
