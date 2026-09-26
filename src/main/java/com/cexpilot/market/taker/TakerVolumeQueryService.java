package com.cexpilot.market.taker;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCoverageValidator;
import com.cexpilot.market.series.SeriesRangeFilter;
import com.cexpilot.market.series.SeriesQueryPolicy;
import com.cexpilot.market.series.SeriesValidation;
import com.cexpilot.market.series.TimePoint;
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
 * 组织 taker 成交量统计查询流程：消解时间 → 选择数据源 → 查询前检查
 * （5m 固定粒度对齐、预算、保留期）→ 适配器外扩取数 → 精确过滤 → 覆盖核对。
 * 与 K 线、持仓量共用同一套 series 骨架。
 */
@Service
public class TakerVolumeQueryService {

    private final Map<Exchange, TakerVolumeSource> sources;
    private final TimeRangeResolver timeRangeResolver;

    public TakerVolumeQueryService(List<TakerVolumeSource> sources, TimeRangeResolver timeRangeResolver) {
        Map<Exchange, TakerVolumeSource> map = new EnumMap<>(Exchange.class);
        for (TakerVolumeSource source : sources) {
            map.put(source.exchange(), source);
        }
        this.sources = map;
        this.timeRangeResolver = timeRangeResolver;
    }

    /**
     * @param userZone    请求上下文时区；spec 自带 timezone 时优先
     * @param spec        LLM 解析出的时间表达
     * @param requestTime 本次请求固定的时间基准
     */
    public TakerVolumeQueryResult query(ZoneId userZone, TimeSpec spec, Instant requestTime,
                                        Exchange exchange, String base) {
        TimeRange range = timeRangeResolver.resolve(userZone, spec, requestTime);
        TakerVolumeSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无 taker 成交量统计数据源: " + exchange.displayName());
        }

        TimeRange effectiveRange = SeriesQueryPolicy.check(
                TakerVolumeSource.INTERVAL, range, source.capability(), exchange.displayName(), requestTime);

        TakerVolumeSource.FetchResult fetch = source.fetch(base,
                effectiveRange.startInclusive().toEpochMilli(),
                effectiveRange.endExclusive().toEpochMilli(), requestTime);
        List<TakerVolumePoint> inRange = SeriesRangeFilter.withinRange(
                fetch.points(), effectiveRange, TakerVolumePoint::timestamp);
        List<TimePoint> points = inRange.stream()
                .map(p -> new TimePoint(p.timestamp()))
                .toList();
        SeriesValidation validation = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.PERIOD,
                TakerVolumeSource.INTERVAL.duration().toMillis(),
                effectiveRange.startInclusive().toEpochMilli(),
                effectiveRange.endExclusive().toEpochMilli(),
                false, points, fetch.abortReason(), requestTime,
                // 外扩只是网格对齐的技术产物；beyond-now 以用户请求终点为准，
                // 否则 to_request_time（终点=请求时刻）会永远被判为部分覆盖
                Math.min(effectiveRange.endExclusive().toEpochMilli(),
                        range.endExclusive().toEpochMilli()));

        Set<Long> acceptedTimes = new HashSet<>();
        for (TimePoint point : validation.points()) {
            acceptedTimes.add(point.ms());
        }
        List<TakerVolumePoint> accepted = inRange.stream()
                .filter(p -> acceptedTimes.contains(p.timestamp()))
                .toList();
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("区间内没有 taker 成交量数据");
        }
        return new TakerVolumeQueryResult(range, effectiveRange, accepted, validation.coverage());
    }
}
