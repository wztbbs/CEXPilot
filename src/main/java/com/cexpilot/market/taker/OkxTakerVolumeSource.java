package com.cexpilot.market.taker;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.OkxClient;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OKX taker 成交量统计数据源：rubik/stat/taker-volume-contract（合约级，5m 周期）。
 * 参数映射（BTC → BTC-USDT-SWAP）、end 游标从区间右端向过去翻页
 * 两端多取一个周期，分页保留重叠，按 timestamp 去重排序；业务范围由查询服务过滤。
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
    public FetchResult fetch(String base, long startMs, long endMs, Instant requestTime) {
        TakerFetchWindow window = TakerFetchWindow.expand(startMs, endMs, requestTime, RETENTION_DAYS);
        if (window.start() >= window.end()) {
            return new FetchResult(List.of(), null);
        }
        String instId = SymbolMapper.okxInstId(base);
        BigDecimal ctVal = ctValProvider.ctVal(instId);
        Map<Long, TakerVolumePoint> byTime = new TreeMap<>();
        String abortReason = null;
        // begin 不含等值：用周期级外扩包含计算范围起点，不依赖 1ms 的边界差异。
        long intervalMs = INTERVAL.duration().toMillis();
        long begin = window.start();
        long end = window.end();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TakerVolumePoint> points = fetcher.page(instId, begin, end, PAGE_LIMIT);
            if (points.isEmpty()) {
                break;
            }
            long oldestTime = Long.MAX_VALUE;
            for (TakerVolumePoint point : points) {
                byTime.putIfAbsent(point.timestamp(), toBaseQty(point, ctVal));
                oldestTime = Math.min(oldestTime, point.timestamp());
            }
            if (points.size() < PAGE_LIMIT) {
                break; // 本页未满：交易所已没有更多数据
            }
            if (oldestTime <= startMs) {
                break; // 已跨过计算范围左端
            }
            long nextEnd = oldestTime + intervalMs;
            if (nextEnd >= end) {
                abortReason = "分页游标不再推进，中止拉取";
                break;
            }
            end = nextEnd;
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
