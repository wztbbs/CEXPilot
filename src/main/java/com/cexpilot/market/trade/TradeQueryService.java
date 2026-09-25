package com.cexpilot.market.trade;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TradePoint;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpec;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织成交历史查询流程：消解时间 → 保留期检查 → 分页拉取 → 完整性校验。
 * 逐笔成交没有预期序列（笔数不可预知），完整性 = 分页自然结束 + 按 ID 去重 +
 * 时间单调 + 全部落在 [start,end)。
 */
@Service
public class TradeQueryService {

    private final Map<Exchange, TradeSource> sources;
    private final TimeRangeResolver timeRangeResolver;

    public TradeQueryService(List<TradeSource> sources, TimeRangeResolver timeRangeResolver) {
        Map<Exchange, TradeSource> map = new EnumMap<>(Exchange.class);
        for (TradeSource source : sources) {
            map.put(source.exchange(), source);
        }
        this.sources = map;
        this.timeRangeResolver = timeRangeResolver;
    }

    public TradeQueryResult query(ZoneId userZone, TimeSpec spec, Instant requestTime,
                                  Exchange exchange, String base) {
        TimeRange range = timeRangeResolver.resolve(userZone, spec, requestTime);
        TradeSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无成交历史数据源: " + exchange.displayName());
        }
        if (source.capability().retentionDays() != null
                && range.startInclusive().toEpochMilli()
                        < requestTime.toEpochMilli() - source.capability().retentionDays() * 86_400_000L) {
            throw new IllegalArgumentException(
                    exchange.displayName() + " 只保留最近 " + source.capability().retentionDays()
                            + " 天的逐笔成交历史，区间起点 " + range.startInclusive() + " 超出可查范围");
        }

        long startMs = range.startInclusive().toEpochMilli();
        long endMs = range.endExclusive().toEpochMilli();
        long nowMs = requestTime.toEpochMilli();
        // 区间终点晚于请求时刻时，只取到请求时刻；尚未到来的部分不可能有数据
        long fetchEndMs = Math.min(endMs, nowMs);

        TradeSource.FetchResult fetch = source.fetch(base, startMs, fetchEndMs);

        // 防御性校验：按 ID 去重、时间升序、剔除越界点（source 已做，这里兜底）
        Set<String> seen = new LinkedHashSet<>();
        List<TradePoint> accepted = new ArrayList<>();
        for (TradePoint trade : fetch.trades()) {
            if (!seen.add(trade.tradeId()) || trade.timestamp() < startMs || trade.timestamp() >= endMs) {
                continue;
            }
            accepted.add(trade);
        }
        accepted.sort(Comparator.comparingLong(TradePoint::timestamp));
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("区间内没有成交数据");
        }
        // 分页自然结束只能说明当前可得数据已取完；区间终点未到时不能宣称完整
        String abortReason = fetch.abortReason();
        if (abortReason == null && endMs > nowMs) {
            abortReason = "区间终点晚于请求基准时间，数据仅覆盖至请求时刻，区间为部分数据";
        }
        return new TradeQueryResult(range, accepted, abortReason == null, abortReason);
    }
}
