package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.OiPoint;
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
import java.util.Arrays;
import java.util.Comparator;
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
                               OiInterval interval, boolean includeUnclosed) {
        return query(userZone, spec, requestTime, exchange, base, interval, includeUnclosed, OiQueryMode.INTERVAL_SAMPLES);
    }

    public OiQueryResult query(ZoneId userZone, TimeSpec spec, Instant requestTime,
                              Exchange exchange, String base, OiInterval interval, boolean includeUnclosed,
                              OiQueryMode mode) {
        TimeRange range = timeRangeResolver.resolve(userZone, spec, requestTime);
        OiSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无持仓量数据源: " + exchange.displayName());
        }

        if (interval == null) interval = selectInterval(source, range, spec);

        OiQueryRequest requested = new OiQueryRequest(
                exchange, base, interval, range, includeUnclosed, mode);
        boolean boundary = mode == OiQueryMode.BOUNDARY_VALUES;
        TimeRange effectiveRange = boundary ? boundaryRange(range, spec, interval) : range;
        // 能力、保留期、原有预算检查复用；端点查询不使用这里的向外对齐结果。
        TimeRange aligned = SeriesQueryPolicy.check(
                interval, effectiveRange, source.capability(), exchange.displayName(), requestTime);
        if (!boundary) effectiveRange = aligned;
        OiQueryRequest effective = new OiQueryRequest(
                exchange, base, interval, effectiveRange, includeUnclosed, mode);

        long startMs = effectiveRange.startInclusive().toEpochMilli();
        long endMs = effectiveRange.endExclusive().toEpochMilli();
        long intervalMs = interval.duration().toMillis();
        if (boundary && (Math.floorMod(startMs, intervalMs) != 0 || Math.floorMod(endMs, intervalMs) != 0
                || !effectiveRange.startInclusive().equals(Instant.ofEpochMilli(startMs))
                || !effectiveRange.endExclusive().equals(Instant.ofEpochMilli(endMs)))) {
            return new OiQueryResult(requested, effective, List.of(), new com.cexpilot.market.series.SeriesCoverage(
                    2, 0, List.of(startMs, endMs), List.of(), List.of(), false, false,
                    "起止端点未对齐所选 OI 采样粒度，无法精确计算；不外扩或插值替代", null, endMs > requestTime.toEpochMilli()));
        }
        if (boundary && (endMs - startMs) / intervalMs + 1 > source.capability().budget()) {
            throw new IllegalArgumentException("持仓量端点统计包含右端采样后超过单次查询预算；请缩短区间或换更粗粒度");
        }

        // Source 契约仍为 [start,end)。多取 1ms 以包含逻辑右端点，不改变窗口时长或未来判断。
        long fetchEndMs = boundary ? Math.addExact(endMs, 1) : endMs;

        OiSource.FetchResult fetch = source.fetch(base, interval,
                startMs, fetchEndMs);
        List<TimePoint> points = fetch.points().stream()
                .map(p -> new TimePoint(p.timestamp()))
                .toList();
        SeriesValidation validation = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.INSTANT,
                interval.duration().toMillis(),
                startMs, fetchEndMs,
                includeUnclosed, points, fetch.abortReason(), requestTime,
                // 外扩只是网格对齐的技术产物；beyond-now 以用户请求终点为准，
                // 否则 to_request_time（终点=请求时刻）会永远被判为部分覆盖
                boundary ? endMs : Math.min(endMs, range.endExclusive().toEpochMilli()));

        Set<Long> acceptedTimes = new HashSet<>();
        for (TimePoint point : validation.points()) {
            acceptedTimes.add(point.ms());
        }
        List<OiPoint> accepted = fetch.points().stream()
                .filter(p -> acceptedTimes.contains(p.timestamp()))
                .sorted(Comparator.comparingLong(OiPoint::timestamp))
                .toList();
        if (accepted.isEmpty() && !boundary) {
            throw new IllegalArgumentException("区间内没有持仓量数据");
        }
        return new OiQueryResult(requested, effective, accepted, validation.coverage());
    }

    private static TimeRange boundaryRange(TimeRange range, TimeSpec spec, OiInterval interval) {
        if (!(spec instanceof TimeSpec.RollingWindow)) return range;
        long step = interval.duration().toMillis();
        Instant end = Instant.ofEpochMilli(Math.floorDiv(range.endExclusive().toEpochMilli(), step) * step);
        // 整体平移：14:03 的最近一小时按 13:00→14:00，不能变成 13:00→14:05。
        return new TimeRange(end.minus(java.time.Duration.between(range.startInclusive(), range.endExclusive())), end, range.timezone());
    }

    private static OiInterval selectInterval(OiSource source, TimeRange range, TimeSpec spec) {
        var supported = Arrays.stream(OiInterval.values())
                .filter(i -> source.capability().supportedIntervals().isEmpty() || source.capability().supportedIntervals().contains(i))
                .sorted(Comparator.comparing(OiInterval::duration)).toList();
        for (var candidate : supported) {
            var effective = boundaryRange(range, spec, candidate);
            long step = candidate.duration().toMillis();
            long start = effective.startInclusive().toEpochMilli(), end = effective.endExclusive().toEpochMilli();
            if (Math.floorMod(start, step) == 0 && Math.floorMod(end, step) == 0
                    && (end - start) / step + 1 <= source.capability().budget()) return candidate;
        }
        if (supported.isEmpty()) throw new IllegalArgumentException("交易所没有可用 OI 采样粒度");
        // 没有可精确表达的粒度时，由统一的对齐/预算检查给出缺口，不偷偷改写窗口。
        return supported.get(0);
    }
}
