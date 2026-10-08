package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.taker.TakerVolumeQueryService;
import com.cexpilot.market.taker.TakerVolumeSource;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.TimeRange;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** taker 成交流量指标直接消费 TakerVolumeQueryService 模型；固定 5m 官方统计，无粒度参数。 */
@Component
public class TakerMetricProvider implements MetricProvider {
    public static final String NAME = "taker";
    private final TakerVolumeQueryService service;

    public TakerMetricProvider(TakerVolumeQueryService service) { this.service = service; }
    @Override public String name() { return NAME; }
    @Override public MetricSelector selector(String name) { return TakerMetric.valueOf(name); }

    @Override
    public MetricResult query(MetricQuery query, RequestContext context) {
        Objects.requireNonNull(context, "请求时间上下文不能为空");
        Objects.requireNonNull(context.requestTime(), "请求时间不能为空");
        Objects.requireNonNull(context.userZone(), "请求时区不能为空");
        if (!(query instanceof TimeRangeQuery timeQuery)) {
            throw new IllegalArgumentException("taker Provider 仅支持时间区间查询: " + query.getClass().getSimpleName());
        }
        var result = service.query(context.userZone(), timeQuery.time(), context.requestTime(),
                timeQuery.exchange(), timeQuery.base());
        var metadata = new MetricResult.SeriesMetadata(result.requested(), result.effective(),
                result.coverage(), TakerVolumeSource.INTERVAL.code(), timeQuery.intervalCode() == null,
                result.points().size());
        if (!result.coverage().rangeComplete()) {
            return new MetricResult.Omitted(metadata, "区间未完整覆盖，不提供指标 value");
        }
        var statistics = MarketCalculator.takerFlowStats(result.points());
        if (statistics == null || result.coverage().coveredUntilMs() == null) {
            throw new IllegalArgumentException("完整区间缺少 taker 统计点或覆盖终点");
        }
        TakerMetric selector = (TakerMetric) query.binding().selector();
        long start = result.points().get(0).timestamp();
        long end = result.coverage().coveredUntilMs();
        return new MetricResult.Scalar(metadata, selector.statistic(statistics),
                BigDecimal.valueOf(end - start, 3),
                new TimeRange(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), result.effective().timezone()));
    }
}
