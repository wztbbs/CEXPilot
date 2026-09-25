package com.cexpilot.market.trade;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.TradePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 币安成交历史数据源：aggTrades（聚合成交口径）。首页按时间窗口定位，
 * 后续按聚合成交 ID（fromId=末笔 ID+1）续页——同一毫秒可能有多笔聚合成交，
 * 按时间戳翻页会漏掉同毫秒尚未返回的数据。按 ID 去重并保留区间边界过滤。
 */
@Component
public class BinanceTradeSource implements TradeSource {

    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 20;

    /** 单页拉取，便于测试注入 fake 页。fromId 为 null 时按时间窗口查询，否则按 ID 续页。 */
    @FunctionalInterface
    interface PageFetcher {
        List<TradePoint> page(String symbol, Long fromId, long startTimeMs, long endTimeMs, int limit);
    }

    private final PageFetcher fetcher;

    @Autowired
    public BinanceTradeSource(BinanceClient client) {
        this((symbol, fromId, startTimeMs, endTimeMs, limit) -> fromId == null
                ? client.aggTrades(symbol, startTimeMs, endTimeMs, limit)
                : client.aggTradesFromId(symbol, fromId, limit));
    }

    BinanceTradeSource(PageFetcher fetcher) {
        this.fetcher = fetcher;
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
    public FetchResult fetch(String base, long startMs, long endMs) {
        String symbol = SymbolMapper.binanceSymbol(base);
        Map<String, TradePoint> byId = new LinkedHashMap<>();
        String abortReason = null;
        Long fromId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TradePoint> trades = fetcher.page(symbol, fromId, startMs, endMs - 1, PAGE_LIMIT);
            if (trades.isEmpty()) {
                break;
            }
            for (TradePoint trade : trades) {
                if (trade.timestamp() >= startMs && trade.timestamp() < endMs) {
                    byId.putIfAbsent(trade.tradeId(), trade);
                }
            }
            if (trades.size() < PAGE_LIMIT) {
                break; // 本页未满：没有更多数据
            }
            TradePoint last = trades.get(trades.size() - 1);
            if (last.timestamp() >= endMs) {
                break; // fromId 页无时间过滤，越过区间右端即收齐
            }
            long nextFrom;
            try {
                nextFrom = Long.parseLong(last.tradeId()) + 1;
            } catch (NumberFormatException e) {
                abortReason = "聚合成交 ID 非数值（" + last.tradeId() + "），无法续页，中止拉取";
                break;
            }
            if (fromId != null && nextFrom <= fromId) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            fromId = nextFrom;
            if (page == MAX_PAGES - 1) {
                abortReason = "分页请求预算用尽（" + MAX_PAGES + " 页，共 " + byId.size()
                        + " 笔），中止拉取；结果为区间前段的部分数据";
            }
        }
        List<TradePoint> sorted = new ArrayList<>(byId.values());
        sorted.sort(java.util.Comparator.comparingLong(TradePoint::timestamp));
        return new FetchResult(sorted, abortReason);
    }
}
