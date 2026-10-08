package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.OiInterval;
import com.cexpilot.time.TimeRange;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
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
                    context.userZone()), MarketCalculator.roundPlain(info.oi(), 10));
        }
        if (!(query instanceof TimeRangeQuery timeQuery)) {
            throw new IllegalArgumentException("oi Provider 仅支持时间区间与快照查询: " + query.getClass().getSimpleName());
        }
        OiInterval interval = timeQuery.intervalCode() == null ? DEFAULT_INTERVAL : OiInterval.parse(timeQuery.intervalCode());
        var result = service.query(context.userZone(), timeQuery.time(), context.requestTime(),
                timeQuery.exchange(), timeQuery.base(), interval, timeQuery.includeUnclosed());
        var metadata = new MetricResult.SeriesMetadata(result.requested().range(), result.effective().range(),
                result.coverage(), interval.code(), timeQuery.intervalCode() == null, result.points().size());
        OiMetric selector = (OiMetric) query.binding().selector();
        if (!query.binding().isRangeStatistic()) {
            return new MetricResult.Series(metadata, result.points().stream()
                    .map(p -> new MetricResult.Sample(Instant.ofEpochMilli(p.timestamp()),
                            MarketCalculator.roundPlain(selector.sample(p), 10))).toList());
        }
        if (!result.coverage().rangeComplete()) {
            return new MetricResult.Omitted(metadata, "区间未完整覆盖，不提供指标 value");
        }
        var statistics = MarketCalculator.oiStats(result.points());
        if (statistics == null || result.coverage().coveredUntilMs() == null) {
            throw new IllegalArgumentException("完整区间缺少持仓量采样或覆盖终点");
        }
        long start = result.points().get(0).timestamp();
        long end = result.coverage().coveredUntilMs();
        return new MetricResult.Scalar(metadata, MarketCalculator.roundPlain(selector.statistic(statistics), 10),
                BigDecimal.valueOf(end - start, 3),
                new TimeRange(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), result.effective().range().timezone()));
    }
}
