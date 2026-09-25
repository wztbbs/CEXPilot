package com.cexpilot.market.trade;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.OkxClient;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.TradePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OKX 成交历史数据源：history-trades（逐笔口径）。
 * 首页用 type=2、after=区间终点（毫秒时间戳）直接锚定到区间右端，避免从最新成交
 * 逐页回溯（高流动合约每分钟数千笔，逐页回溯不可行）；之后用 type=1、
 * after=最旧 tradeId 向过去翻页（同一毫秒可能多笔，tradeId 唯一不会错位），
 * 直到触及区间左端。sz（张）按合约面值 ctVal 换算为基础币数量。
 * 文档口径为最近约 3 个月（capability.retentionDays=90）。
 */
@Component
public class OkxTradeSource implements TradeSource {

    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 50;
    private static final int RETENTION_DAYS = 90;

    /** 单页拉取，便于测试注入 fake 页。type=2 时 after 为毫秒时间戳，type=1 时为 tradeId。 */
    @FunctionalInterface
    interface PageFetcher {
        List<TradePoint> page(String instId, String type, String after, int limit);
    }

    @FunctionalInterface
    interface CtValProvider {
        BigDecimal ctVal(String instId);
    }

    private final PageFetcher fetcher;
    private final CtValProvider ctValProvider;

    @Autowired
    public OkxTradeSource(OkxClient client) {
        this((instId, type, after, limit) -> client.historyTrades(instId, type, null, after, limit),
                client::instrumentCtVal);
    }

    OkxTradeSource(PageFetcher fetcher, CtValProvider ctValProvider) {
        this.fetcher = fetcher;
        this.ctValProvider = ctValProvider;
    }

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(), PAGE_LIMIT, MAX_PAGES, RETENTION_DAYS);
    }

    @Override
    public FetchResult fetch(String base, long startMs, long endMs) {
        String instId = SymbolMapper.okxInstId(base);
        BigDecimal ctVal = ctValProvider.ctVal(instId);
        Map<String, TradePoint> byId = new TreeMap<>();
        String abortReason = null;
        // 首页：type=2 用时间戳锚定到区间右端；之后：type=1 按 tradeId 翻页
        String type = "2";
        String after = String.valueOf(endMs);
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TradePoint> trades = fetcher.page(instId, type, after, PAGE_LIMIT);
            if (trades.isEmpty()) {
                break;
            }
            String oldestId = null;
            long oldestTime = Long.MAX_VALUE;
            for (TradePoint trade : trades) {
                if (trade.timestamp() >= startMs && trade.timestamp() < endMs) {
                    byId.putIfAbsent(trade.tradeId(), toBaseQty(trade, ctVal));
                }
                if (trade.timestamp() < oldestTime) {
                    oldestTime = trade.timestamp();
                    oldestId = trade.tradeId();
                }
            }
            if (oldestTime < startMs) {
                break; // 已触及区间左端
            }
            if (trades.size() < PAGE_LIMIT) {
                break; // 本页未满：没有更早的数据
            }
            type = "1";
            if (oldestId == null || oldestId.equals(after)) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            after = oldestId;
            if (page == MAX_PAGES - 1) {
                abortReason = "分页请求预算用尽（" + MAX_PAGES + " 页，共 " + byId.size()
                        + " 笔），中止拉取；结果为区间后段的部分数据";
            }
        }
        List<TradePoint> sorted = new ArrayList<>(byId.values());
        sorted.sort(java.util.Comparator.comparingLong(TradePoint::timestamp));
        return new FetchResult(sorted, abortReason);
    }

    private static TradePoint toBaseQty(TradePoint trade, BigDecimal ctVal) {
        return new TradePoint(trade.tradeId(), trade.timestamp(), trade.price(),
                trade.qty().multiply(ctVal), trade.takerBuy());
    }
}
