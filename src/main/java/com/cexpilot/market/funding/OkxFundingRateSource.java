package com.cexpilot.market.funding;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.OkxClient;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OKX 资金费率历史数据源：参数映射（BTC → BTC-USDT-SWAP）、after 游标从区间右端
 * 向过去翻页、结算时间网格吸附、按 fundingTime 去重排序。
 * 结算周期 = fundingRate 接口的 nextFundingTime - fundingTime。
 */
@Component
public class OkxFundingRateSource implements FundingRateSource {

    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 60;

    /** 单页拉取，便于测试注入 fake 页。 */
    @FunctionalInterface
    interface PageFetcher {
        List<FundingRatePoint> page(String instId, long beforeTs, long afterTs, int limit);
    }

    @FunctionalInterface
    interface IntervalProvider {
        long intervalMs(String instId);
    }

    private final PageFetcher fetcher;
    private final IntervalProvider intervalProvider;

    @Autowired
    public OkxFundingRateSource(OkxClient client) {
        this(client::fundingRateHistory, instId -> {
            var snapshot = client.fundingRate(instId);
            long intervalMs = snapshot.nextFundingTime() - snapshot.fundingTime();
            if (intervalMs <= 0) {
                throw new IllegalArgumentException("无法确定 " + instId + " 的结算周期");
            }
            // 周期归一到整小时，与币安口径一致
            return Math.round(intervalMs / 3_600_000.0) * 3_600_000L;
        });
    }

    OkxFundingRateSource(PageFetcher fetcher, IntervalProvider intervalProvider) {
        this.fetcher = fetcher;
        this.intervalProvider = intervalProvider;
    }

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(), PAGE_LIMIT, MAX_PAGES);
    }

    @Override
    public long fundingIntervalMs(String base) {
        return intervalProvider.intervalMs(SymbolMapper.okxInstId(base));
    }

    @Override
    public FetchResult fetch(String base, long intervalMs, long startMs, long endMs) {
        String instId = SymbolMapper.okxInstId(base);
        Map<Long, FundingRatePoint> byTime = new TreeMap<>();
        String abortReason = null;
        // before=startMs-1：包含 startMs 本身（OKX before 为“比该 ts 更新”，不含等值）
        long before = startMs - 1;
        long after = endMs;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<FundingRatePoint> points = fetcher.page(instId, before, after, PAGE_LIMIT);
            if (points.isEmpty()) {
                break;
            }
            long oldestTime = Long.MAX_VALUE;
            for (FundingRatePoint point : points) {
                long snapped = SettlementGridSnap.snap(point.fundingTime(), intervalMs);
                if (snapped >= startMs && snapped < endMs) {
                    byTime.putIfAbsent(snapped, new FundingRatePoint(point.rate(), snapped));
                }
                oldestTime = Math.min(oldestTime, point.fundingTime());
            }
            if (points.size() < PAGE_LIMIT) {
                break; // 本页未满：交易所已没有更多数据
            }
            if (oldestTime >= after) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            if (oldestTime <= startMs) {
                break; // 已触及区间左端
            }
            after = oldestTime;
            if (page == MAX_PAGES - 1) {
                abortReason = "分页请求预算用尽（" + MAX_PAGES + " 页），中止拉取";
            }
        }
        return new FetchResult(new ArrayList<>(byTime.values()), abortReason);
    }
}
