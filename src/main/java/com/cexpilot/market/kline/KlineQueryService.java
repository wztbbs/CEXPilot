package com.cexpilot.market.kline;

import com.cexpilot.market.series.BoundaryMode;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.Candle;
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
 * 组织整个 K 线查询流程：消解时间 → 选择数据源 → 查询前检查 →
 * 分页拉取 → 覆盖核对。各组件完成自己的工作，这里只做编排。
 */
@Service
public class KlineQueryService {

    private final Map<Exchange, KlineSource> sources;
    private final TimeRangeResolver timeRangeResolver;

    public KlineQueryService(List<KlineSource> sources, TimeRangeResolver timeRangeResolver) {
        Map<Exchange, KlineSource> map = new EnumMap<>(Exchange.class);
        for (KlineSource source : sources) {
            map.put(source.exchange(), source);
        }
        this.sources = map;
        this.timeRangeResolver = timeRangeResolver;
    }

    /**
     * @param userZone    请求上下文时区；spec 自带 timezone 时优先
     * @param spec        LLM 解析出的时间表达
     * @param requestTime 本次请求固定的时间基准，时间消解与未收盘判定共用
     */
    public KlineQueryResult query(ZoneId userZone, TimeSpec spec, Instant requestTime,
                                  Exchange exchange, String base,
                                  CandleInterval interval, BoundaryMode boundaryMode, boolean includeUnclosed) {
        TimeRange range = timeRangeResolver.resolve(userZone, spec, requestTime);
        KlineSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无 K 线数据源: " + exchange.displayName());
        }

        KlineQueryRequest requested = new KlineQueryRequest(
                exchange, base, interval, range, boundaryMode, includeUnclosed);
        TimeRange effectiveRange = SeriesQueryPolicy.check(
                interval, range, boundaryMode, source.capability(), exchange.displayName(), requestTime);
        KlineQueryRequest effective = new KlineQueryRequest(
                exchange, base, interval, effectiveRange, boundaryMode, includeUnclosed);

        KlineSource.FetchResult fetch = source.fetch(effective);
        List<TimePoint> points = fetch.candles().stream()
                .map(c -> new TimePoint(c.openTime(), c.confirmed()))
                .toList();
        SeriesValidation validation = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.PERIOD,
                interval.duration().toMillis(),
                effectiveRange.startInclusive().toEpochMilli(),
                effectiveRange.endExclusive().toEpochMilli(),
                includeUnclosed, points, fetch.abortReason(), requestTime,
                // cover 外延只是网格对齐的技术产物；beyond-now 以用户请求终点为准，
                // 否则 to_request_time（终点=请求时刻）配 cover 会永远被判为部分覆盖
                Math.min(effectiveRange.endExclusive().toEpochMilli(),
                        range.endExclusive().toEpochMilli()));

        Set<Long> acceptedTimes = new HashSet<>();
        for (TimePoint point : validation.points()) {
            acceptedTimes.add(point.ms());
        }
        List<Candle> accepted = fetch.candles().stream()
                .filter(c -> acceptedTimes.contains(c.openTime()))
                .toList();
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("未获取到K线数据");
        }
        return new KlineQueryResult(requested, effective, accepted, validation.coverage());
    }
}
