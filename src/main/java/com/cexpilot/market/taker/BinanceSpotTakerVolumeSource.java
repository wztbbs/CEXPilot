package com.cexpilot.market.taker;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketType;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 现货 5m K 线提供主动买量，总量减主动买量得到主动卖量；覆盖仍由公共核对器判断。 */
@Component
public class BinanceSpotTakerVolumeSource implements TakerVolumeSource {
    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 4;
    private final BinanceClient client;
    public BinanceSpotTakerVolumeSource(BinanceClient client) { this.client = client; }
    @Override public Exchange exchange() { return Exchange.BINANCE; }
    @Override public MarketType marketType() { return MarketType.SPOT; }
    @Override public SeriesCapability capability() { return new SeriesCapability(java.util.Set.of(INTERVAL), PAGE_LIMIT, MAX_PAGES); }

    @Override public FetchResult fetch(String base, long startMs, long endMs, Instant requestTime) {
        var points = new TreeMap<Long, TakerVolumePoint>();
        long cursor = startMs;
        // 未完结 K 线由覆盖核对器排除，不能用部分成交量冒充整根统计。
        String abort = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<TakerVolumePoint> rows = client.spotTakerVolumes(SymbolMapper.binanceSymbol(base), cursor, endMs - 1, PAGE_LIMIT);
            if (rows.isEmpty()) break;
            long last = Long.MIN_VALUE;
            for (var point : rows) {
                if (point.timestamp() >= startMs && point.timestamp() < endMs) points.putIfAbsent(point.timestamp(), point);
                last = Math.max(last, point.timestamp());
            }
            long next = last + INTERVAL.duration().toMillis();
            if (next <= cursor) { abort = "分页游标不再推进，中止拉取"; break; }
            if (next >= endMs || rows.size() < PAGE_LIMIT) break;
            cursor = next;
            if (page == MAX_PAGES - 1) abort = "分页请求预算用尽（4 页），中止拉取";
        }
        return new FetchResult(new ArrayList<>(points.values()), abort);
    }
}
