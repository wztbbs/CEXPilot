package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.OkxClient;
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
 * OKX 持仓量历史数据源：参数映射（BTC → BTC-USDT-SWAP，粒度 code → period）、
 * end 游标从区间右端向过去翻页（begin 固定为区间左端）、按 timestamp 去重排序。
 * 历史深度实测足够（40 天以上），capability 不设保留期限制。
 */
@Component
public class OkxOiSource implements OiSource {

    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 40;

    /** 单页拉取，便于测试注入 fake 页。 */
    @FunctionalInterface
    interface PageFetcher {
        List<OiPoint> page(String instId, String period, long beginMs, long endMs, int limit);
    }

    private final PageFetcher fetcher;

    @Autowired
    public OkxOiSource(OkxClient client) {
        this(client::openInterestHistory);
    }

    OkxOiSource(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(OiInterval.values()), PAGE_LIMIT, MAX_PAGES);
    }

    /**
     * OKX period 编码：6H/12H/1D 默认是 UTC+8 网格（与覆盖核对的 UTC 网格错位，
     * 实测 1D 返回 UTC 16:00 的点），必须用 utc 变体；其余粒度两种网格一致。
     */
    static String period(OiInterval interval) {
        return switch (interval) {
            case ONE_HOUR -> "1H";
            case TWO_HOURS -> "2H";
            case FOUR_HOURS -> "4H";
            case SIX_HOURS -> "6Hutc";
            case TWELVE_HOURS -> "12Hutc";
            case ONE_DAY -> "1Dutc";
            default -> interval.code();
        };
    }

    @Override
    public FetchResult fetch(String base, OiInterval interval, long startMs, long endMs) {
        String instId = SymbolMapper.okxInstId(base);
        String period = period(interval);
        Map<Long, OiPoint> byTime = new TreeMap<>();
        String abortReason = null;
        // begin 不含等值（实测 begin=00:00 时不返回 00:00 的采样），左移 1ms 包含区间起点
        long begin = startMs - 1;
        long end = endMs;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<OiPoint> points = fetcher.page(instId, period, begin, end, PAGE_LIMIT);
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
                break; // 本页未满：交易所已没有更多数据
            }
            if (oldestTime >= end) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            if (oldestTime <= startMs) {
                break; // 已触及区间左端
            }
            end = oldestTime;
            if (page == MAX_PAGES - 1) {
                abortReason = "分页请求预算用尽（" + MAX_PAGES + " 页），中止拉取";
            }
        }
        return new FetchResult(new ArrayList<>(byTime.values()), abortReason);
    }
}
