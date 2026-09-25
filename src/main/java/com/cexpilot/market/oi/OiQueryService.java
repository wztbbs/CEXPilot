package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.series.BoundaryMode;
import com.cexpilot.market.series.SeriesCoverageValidator;
import com.cexpilot.market.series.SeriesQueryPolicy;
import com.cexpilot.market.series.SeriesValidation;
import com.cexpilot.market.series.TimePoint;
import com.cexpilot.time.OiInterval;
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
 * 组织持仓量历史查询流程：消解时间 → 选择数据源 → 查询前检查（粒度/对齐/预算/保留期）→
 * 分页拉取 → 覆盖核对。与 K 线、资金费率共用同一套 series 骨架。
 */
@Service
public class OiQueryService {

    private final Map<Exchange, OiSource> sources;
    private final TimeRangeResolver timeRangeResolver;

    public OiQueryService(List<OiSource> sources, TimeRangeResolver timeRangeResolver) {
        Map<Exchange, OiSource> map = new EnumMap<>(Exchange.class);
        for (OiSource source : sources) {
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
    public OiQueryResult query(ZoneId userZone, TimeSpec spec, Instant requestTime,
                               Exchange exchange, String base,
                               OiInterval interval, BoundaryMode boundaryMode, boolean includeUnclosed) {
        TimeRange range = timeRangeResolver.resolve(userZone, spec, requestTime);
        OiSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无持仓量数据源: " + exchange.displayName());
        }

        OiQueryRequest requested = new OiQueryRequest(
                exchange, base, interval, range, boundaryMode, includeUnclosed);
        TimeRange effectiveRange = SeriesQueryPolicy.check(
                interval, range, boundaryMode, source.capability(), exchange.displayName(), requestTime);
        OiQueryRequest effective = new OiQueryRequest(
                exchange, base, interval, effectiveRange, boundaryMode, includeUnclosed);

        OiSource.FetchResult fetch = source.fetch(base, interval,
                effectiveRange.startInclusive().toEpochMilli(),
                effectiveRange.endExclusive().toEpochMilli());
        List<TimePoint> points = fetch.points().stream()
                .map(p -> new TimePoint(p.timestamp()))
                .toList();
        SeriesValidation validation = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.INSTANT,
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
        List<OiPoint> accepted = fetch.points().stream()
                .filter(p -> acceptedTimes.contains(p.timestamp()))
                .toList();
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("区间内没有持仓量数据");
        }
        return new OiQueryResult(requested, effective, accepted, validation.coverage());
    }
}
