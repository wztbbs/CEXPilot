package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.market.oi.OiQueryMode;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.OiInterval;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Objects;

/** 持仓量指标直接消费 OiQueryService 模型；采样点是 INSTANT 语义，观测窗口为首末采样点跨度。 */
@Component
public class OiMetricProvider implements MetricProvider {
    public static final String NAME = "oi";
    /** 计划未指定粒度时的缺省采样粒度。 */
    private static final OiInterval DEFAULT_INTERVAL = OiInterval.parse("1h");
    private final OiQueryService service;
    private final MarketDataService market;

    public OiMetricProvider(OiQueryService service, MarketDataService market) {
        this.service = service;
        this.market = market;
    }
    @Override public String name() { return NAME; }
    @Override public MetricSelector selector(String name) { return OiMetric.valueOf(name); }

    @Override
    public MetricResult query(MetricQuery query, RequestContext context) {
        Objects.requireNonNull(context, "请求时间上下文不能为空");
        if (!"perpetual".equals(query.binding().instrument().path("market_type").asText()))
            throw new IllegalArgumentException("此 Provider 仅支持永续市场");
        Objects.requireNonNull(context.requestTime(), "请求时间不能为空");
        Objects.requireNonNull(context.userZone(), "请求时区不能为空");
        if (query instanceof SnapshotQuery snapshot) {
            if (snapshot.binding().selector() == null || !((OiMetric) snapshot.binding().selector()).supportsSnapshot()) {
                throw new IllegalArgumentException("oi 快照仅支持 oi.quantity");
            }
            var info = market.oiSnapshot(snapshot.exchange(), snapshot.base());
            if (info == null || info.oi() == null) throw new IllegalArgumentException("持仓量快照缺少数值");
            if (info.dataTime() <= 0) throw new IllegalArgumentException("持仓量快照缺少数据时间，不能标注 as_of");
            return new MetricResult.Point(new MetricResult.SnapshotMetadata(Instant.ofEpochMilli(info.dataTime()),
                    context.userZone()), MarketCalculator.roundPlain(info.oi(), 8));
        }
        if (!(query instanceof TimeRangeQuery timeQuery)) {
            throw new IllegalArgumentException("oi Provider 仅支持时间区间与快照查询: " + query.getClass().getSimpleName());
        }
        OiMetric selector = (OiMetric) query.binding().selector();
        boolean boundary = selector.usesBoundaryValues();
        OiInterval interval = timeQuery.intervalCode() == null ? (boundary ? null : DEFAULT_INTERVAL) : OiInterval.parse(timeQuery.intervalCode());
        var result = boundary
                ? service.query(context.userZone(), timeQuery.time(), context.requestTime(), timeQuery.exchange(), timeQuery.base(),
                        interval, timeQuery.includeUnclosed(), OiQueryMode.BOUNDARY_VALUES)
                : service.query(context.userZone(), timeQuery.time(), context.requestTime(), timeQuery.exchange(), timeQuery.base(),
                        interval, timeQuery.includeUnclosed());
        var metadata = new MetricResult.SeriesMetadata(result.requested().range(), result.effective().range(),
                result.coverage(), result.effective().interval().code(), timeQuery.intervalCode() == null, result.points().size());
        if (!query.binding().isRangeStatistic()) {
            return new MetricResult.Series(metadata, result.points().stream()
                    .map(p -> new MetricResult.Sample(Instant.ofEpochMilli(p.timestamp()),
                            MarketCalculator.roundPlain(selector.sample(p), 8))).toList());
        }
        if (!result.coverage().rangeComplete()) {
            return new MetricResult.Omitted(metadata, boundary
                    ? "OI 端点或区间采样未齐/窗口无法按粒度精确表达，不以相邻采样替代，不提供指标 value"
                    : "区间未完整覆盖，不提供指标 value");
        }
        var points = result.points();
        if (points.isEmpty()) return new MetricResult.Omitted(metadata, "区间内没有有效 OI 采样");
        var first = points.get(0);
        var last = points.get(points.size()-1);
        if (boundary && (first.timestamp() != result.effective().range().startInclusive().toEpochMilli()
                || last.timestamp() != result.effective().range().endExclusive().toEpochMilli())) {
            return new MetricResult.Omitted(metadata, "缺少精确 OI 起止端点，不以区间内首末点替代");
        }
        if ((selector == OiMetric.CHANGE || selector == OiMetric.CHANGE_PCT) && first.timestamp() >= last.timestamp()) {
            return new MetricResult.Omitted(metadata, "OI 变化至少需要两个不同时刻的采样点");
        }
        if (selector == OiMetric.CHANGE_PCT && first.oi().signum() == 0) {
            return new MetricResult.Omitted(metadata, "期初 OI 为零，变化率不可定义");
        }
        var statistics = MarketCalculator.oiStats(points);
        long start = selector == OiMetric.END ? last.timestamp() : first.timestamp();
        long end = selector == OiMetric.START ? first.timestamp() : last.timestamp();
        return new MetricResult.SampledScalar(metadata, MarketCalculator.roundPlain(selector.statistic(statistics), 8),
                Instant.ofEpochMilli(start), Instant.ofEpochMilli(end));
    }
}
