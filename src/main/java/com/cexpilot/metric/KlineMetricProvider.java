package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.kline.KlineQueryService;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** K 线指标直接消费查询服务模型，序列投影和区间统计均不经过旧行情 Tool。 */
@Component
public class KlineMetricProvider implements MetricProvider {
    public static final String NAME = "kline";
    private final KlineQueryService service;

    public KlineMetricProvider(KlineQueryService service) { this.service = service; }
    @Override public String name() { return NAME; }
    @Override public MetricSelector selector(String name) { return KlineMetric.valueOf(name); }

    @Override
    public MetricResult query(MetricQuery query, RequestContext context) {
        Objects.requireNonNull(context, "请求时间上下文不能为空");
        Objects.requireNonNull(context.requestTime(), "请求时间不能为空");
        Objects.requireNonNull(context.userZone(), "请求时区不能为空");
        if (!(query instanceof TimeRangeQuery timeQuery)) {
            throw new IllegalArgumentException("kline Provider 仅支持时间区间查询: " + query.getClass().getSimpleName());
        }
        CandleInterval interval = timeQuery.intervalCode() == null ? null : CandleInterval.parse(timeQuery.intervalCode());
        var result = service.query(context.userZone(), timeQuery.time(), context.requestTime(),
                timeQuery.exchange(), timeQuery.base(), interval, timeQuery.includeUnclosed());
        var metadata = new MetricResult.SeriesMetadata(result.requested().range(), result.effective().range(),
                result.coverage(), result.effective().interval().code(), interval == null, result.candles().size());
        KlineMetric selector = (KlineMetric) query.binding().selector();
        if (!query.binding().isRangeStatistic()) {
            return new MetricResult.Series(metadata, result.candles().stream()
                    .map(c -> new MetricResult.Sample(Instant.ofEpochMilli(c.openTime()), selector.sample(c))).toList());
        }
        if (!result.coverage().rangeComplete()) {
            return new MetricResult.Omitted(metadata, "区间未完整覆盖，不提供指标 value");
        }
        var statistics = MarketCalculator.rangeStats(result.candles());
        if (statistics == null || result.coverage().coveredUntilMs() == null) {
            throw new IllegalArgumentException("完整区间缺少 K 线或覆盖终点");
        }
        long start = result.candles().get(0).openTime();
        long end = result.coverage().coveredUntilMs();
        return new MetricResult.Scalar(metadata, selector.statistic(statistics),
                BigDecimal.valueOf(end - start, 3),
                new TimeRange(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), result.effective().range().timezone()));
    }
}
