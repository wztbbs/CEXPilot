package com.cexpilot.market.taker;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.OkxClient;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.TakerVolumePoint;
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
 * OKX taker 成交量统计数据源：rubik/stat/taker-volume-contract（合约级，5m 周期）。
 * 参数映射（BTC → BTC-USDT-SWAP）、end 游标从区间右端向过去翻页
 * （begin 固定为区间左端-1ms：OKX begin 不含等值，左移 1ms 包含区间起点）、
 * 按 timestamp 去重排序。
 * 接口返回的 buyVol/sellVol 为合约张数（实测 5m 总量约 4~5 万张，与 BTC-USDT-SWAP
 * ctVal=0.01 换算后数百 BTC 的量级吻合；若按基础币解读则比币安同窗口高两个数量级，
 * 不成立），按合约面值 ctVal 换算为基础币数量（同 OkxTradeSource 的张→币换算）。
 * 历史保留期未在文档中给出，按保守 30 天处理（capability.retentionDays=30，policy 前置拦截）。
 */
@Component
public class OkxTakerVolumeSource implements TakerVolumeSource {

    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 90;
    private static final int RETENTION_DAYS = 30;

    /** 单页拉取，便于测试注入 fake 页。返回值为合约张数口径。 */
    @FunctionalInterface
    interface PageFetcher {
        List<TakerVolumePoint> page(String instId, long beginMs, long endMs, int limit);
    }

    @FunctionalInterface
    interface CtValProvider {
        BigDecimal ctVal(String instId);
    }

    private final PageFetcher fetcher;
    private final CtValProvider ctValProvider;

    @Autowired
    public OkxTakerVolumeSource(OkxClient client) {
        this((instId, beginMs, endMs, limit) ->
                        client.takerVolumeContract(instId, INTERVAL.code(), beginMs, endMs, limit),
                client::instrumentCtVal);
    }

    OkxTakerVolumeSource(PageFetcher fetcher, CtValProvider ctValProvider) {
        this.fetcher = fetcher;
        this.ctValProvider = ctValProvider;
    }

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public SeriesCapability capability() {
        return new SeriesCapability(Set.of(INTERVAL), PAGE_LIMIT, MAX_PAGES, RETENTION_DAYS);
    }

    @Override
    public FetchResult fetch(String base, long startMs, long endMs) {
        String instId = SymbolMapper.okxInstId(base);
        BigDecimal ctVal = ctValProvider.ctVal(instId);
        Map<Long, TakerVolumePoint> byTime = new TreeMap<>();
        String abortReason = null;
        // begin 不含等值（实测 begin=00:00 时不返回 00:00 的点），左移 1ms 包含区间起点
        long begin = startMs - 1;
        long end = endMs;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TakerVolumePoint> points = fetcher.page(instId, begin, end, PAGE_LIMIT);
            if (points.isEmpty()) {
                break;
            }
            long oldestTime = Long.MAX_VALUE;
            for (TakerVolumePoint point : points) {
                if (point.timestamp() >= startMs && point.timestamp() < endMs) {
                    byTime.putIfAbsent(point.timestamp(), toBaseQty(point, ctVal));
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

    private static TakerVolumePoint toBaseQty(TakerVolumePoint point, BigDecimal ctVal) {
        return new TakerVolumePoint(point.timestamp(),
                point.buyVolume().multiply(ctVal), point.sellVolume().multiply(ctVal));
    }
}
