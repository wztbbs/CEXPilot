package com.cexpilot.market.funding;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 币安资金费率历史数据源：参数映射（BTC → BTCUSDT）、startTime 游标分页、
 * 结算时间网格吸附、按 fundingTime 去重排序。结算周期来自 /fapi/v1/fundingInfo。
 */
@Component
public class BinanceFundingRateSource implements FundingRateSource {

    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 10;

    /** 单页拉取，便于测试注入 fake 页。 */
    @FunctionalInterface
    interface PageFetcher {
        List<FundingRatePoint> page(String symbol, long startTimeMs, long endTimeMs, int limit);
    }

    @FunctionalInterface
    interface IntervalProvider {
        long intervalMs(String symbol);
    }

    private final PageFetcher fetcher;
    private final IntervalProvider intervalProvider;

    @Autowired
    public BinanceFundingRateSource(BinanceClient client) {
        this(client::fundingRateHistory, symbol -> {
            // fundingInfo 全量接口响应过大（经代理实测 30s+ 读不完），改为从最近结算点推导周期。
            // 接口升序返回且 limit 截断的是最早的 N 条：必须用足够大的 limit 拉全窗口，
            // 再取真正最近的两期（用 limit=20 取到的只是 8 天前最早的 20 条）
            long now = System.currentTimeMillis();
            List<FundingRatePoint> recent = client.fundingRateHistory(
                    symbol, now - 8 * 86_400_000L, now, 1000);
            if (recent.size() < 2) {
                throw new IllegalArgumentException("无法确定 " + symbol + " 的结算周期（历史结算点不足）");
            }
            long diffMs = recent.get(recent.size() - 1).fundingTime()
                    - recent.get(recent.size() - 2).fundingTime();
            // 结算时间戳有毫秒级抖动，周期归一到整小时（结算周期只按小时定义：8h/4h/1h 等）
            return Math.round(diffMs / 3_600_000.0) * 3_600_000L;
        });
    }

    BinanceFundingRateSource(PageFetcher fetcher, IntervalProvider intervalProvider) {
        this.fetcher = fetcher;
        this.intervalProvider = intervalProvider;
    }

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(), PAGE_LIMIT, MAX_PAGES);
    }

    @Override
    public long fundingIntervalMs(String base) {
        return intervalProvider.intervalMs(SymbolMapper.binanceSymbol(base));
    }

    @Override
    public FetchResult fetch(String base, long intervalMs, long startMs, long endMs) {
        String symbol = SymbolMapper.binanceSymbol(base);
        Map<Long, FundingRatePoint> byTime = new LinkedHashMap<>();
        String abortReason = null;
        long cursor = startMs;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<FundingRatePoint> points = fetcher.page(symbol, cursor, endMs - 1, PAGE_LIMIT);
            if (points.isEmpty()) {
                break;
            }
            long lastTime = Long.MIN_VALUE;
            for (FundingRatePoint point : points) {
                long snapped = SettlementGridSnap.snap(point.fundingTime(), intervalMs);
                if (snapped >= startMs && snapped < endMs) {
                    byTime.putIfAbsent(snapped, new FundingRatePoint(point.rate(), snapped));
                }
                lastTime = Math.max(lastTime, point.fundingTime());
            }
            if (points.size() < PAGE_LIMIT) {
                break; // 本页未满：交易所已没有更多数据
            }
            long next = lastTime + 1;
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
        return new FetchResult(new ArrayList<>(byTime.values()), abortReason);
    }
}
