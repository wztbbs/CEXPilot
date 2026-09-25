package com.cexpilot.market.markprice;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.BoundaryMode;
import com.cexpilot.market.series.SeriesCoverageValidator;
import com.cexpilot.market.series.SeriesQueryPolicy;
import com.cexpilot.market.series.SeriesValidation;
import com.cexpilot.market.series.TimePoint;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpec;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织标记/指数价格历史查询流程：消解时间 → 选择数据源 → 查询前检查 →
 * 分页拉取 → 覆盖核对。与 K 线共用同一套 series 骨架（PERIOD 语义）。
 */
@Service
public class MarkPriceQueryService {

    private final Map<Exchange, MarkPriceSource> sources;
    private final TimeRangeResolver timeRangeResolver;

    public MarkPriceQueryService(List<MarkPriceSource> sources, TimeRangeResolver timeRangeResolver) {
        Map<Exchange, MarkPriceSource> map = new EnumMap<>(Exchange.class);
        for (MarkPriceSource source : sources) {
            map.put(source.exchange(), source);
        }
        this.sources = map;
        this.timeRangeResolver = timeRangeResolver;
    }

    public MarkPriceQueryResult query(ZoneId userZone, TimeSpec spec, Instant requestTime,
                                      Exchange exchange, String base, PriceType priceType,
                                      CandleInterval interval, BoundaryMode boundaryMode,
                                      boolean includeUnclosed) {
        TimeRange range = timeRangeResolver.resolve(userZone, spec, requestTime);
        MarkPriceSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无标记价格数据源: " + exchange.displayName());
        }

        TimeRange effective = SeriesQueryPolicy.check(
                interval, range, boundaryMode, source.capability(), exchange.displayName(), requestTime);

        MarkPriceSource.FetchResult fetch = source.fetch(base, priceType, interval,
                effective.startInclusive().toEpochMilli(), effective.endExclusive().toEpochMilli());
        List<TimePoint> points = fetch.candles().stream()
                .map(c -> new TimePoint(c.openTime(), c.confirmed()))
                .toList();
        SeriesValidation validation = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.PERIOD,
                interval.duration().toMillis(),
                effective.startInclusive().toEpochMilli(),
                effective.endExclusive().toEpochMilli(),
                includeUnclosed, points, fetch.abortReason(), requestTime,
                // cover 外延只是网格对齐的技术产物；beyond-now 以用户请求终点为准，
                // 否则 to_request_time（终点=请求时刻）配 cover 会永远被判为部分覆盖
                Math.min(effective.endExclusive().toEpochMilli(),
                        range.endExclusive().toEpochMilli()));

        Set<Long> acceptedTimes = new HashSet<>();
        for (TimePoint point : validation.points()) {
            acceptedTimes.add(point.ms());
        }
        List<Candle> accepted = fetch.candles().stream()
                .filter(c -> acceptedTimes.contains(c.openTime()))
                .toList();
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("区间内没有价格数据");
        }
        return new MarkPriceQueryResult(priceType, range, effective, accepted, validation.coverage());
    }
}
