package com.cexpilot.metric;

import com.cexpilot.market.MarketCalculator;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeRange;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 标记/指数价格指标直接消费 MarkPriceQueryService 模型；区间统计复用 priceChange 计算路径。 */
@Component
public class MarkPriceMetricProvider implements MetricProvider {
    public static final String NAME = "mark_price";
    private final MarkPriceQueryService service;
    private final MarketDataService market;

    public MarkPriceMetricProvider(MarkPriceQueryService service, MarketDataService market) {
        this.service = service;
        this.market = market;
    }
    @Override public String name() { return NAME; }

    @Override
    public MetricSelector selector(String name) {
        for (MarkPriceMetric metric : MarkPriceMetric.values()) {
            if (metric.name().equals(name)) return metric;
        }
        for (MarkPriceSnapshot snapshot : MarkPriceSnapshot.values()) {
            if (snapshot.name().equals(name)) return snapshot;
        }
        throw new IllegalArgumentException("未接入标记价选择器: " + name);
    }

    @Override
    public MetricResult query(MetricQuery query, RequestContext context) {
        Objects.requireNonNull(context, "请求时间上下文不能为空");
        Objects.requireNonNull(context.requestTime(), "请求时间不能为空");
        Objects.requireNonNull(context.userZone(), "请求时区不能为空");
        if (query instanceof SnapshotQuery snapshot) {
            if (!(snapshot.binding().selector() instanceof MarkPriceSnapshot selector)) {
                throw new IllegalArgumentException("标记价快照选择器不可用: " + snapshot.binding().selector());
            }
            var price = market.markPrice(snapshot.exchange(), snapshot.base());
            if (price == null) throw new IllegalArgumentException("交易所未返回标记价格快照");
            BigDecimal value = selector.value(price);
            long dataTime = selector.time(price);
            if (dataTime <= 0) throw new IllegalArgumentException("标记价快照缺少数据时间，不能标注 as_of");
            if (value == null) {
                return new MetricResult.Omitted(new MetricResult.SnapshotMetadata(
                        Instant.ofEpochMilli(dataTime), context.userZone()), "交易所未提供该价格");
            }
            return new MetricResult.Point(new MetricResult.SnapshotMetadata(
                    Instant.ofEpochMilli(dataTime), context.userZone()), value);
        }
        if (!(query instanceof TimeRangeQuery timeQuery)) {
            throw new IllegalArgumentException("mark_price Provider 仅支持时间区间与快照查询: " + query.getClass().getSimpleName());
        }
        CandleInterval interval = timeQuery.intervalCode() == null ? null : CandleInterval.parse(timeQuery.intervalCode());
        MarkPriceMetric selector = (MarkPriceMetric) query.binding().selector();
        var result = service.query(context.userZone(), timeQuery.time(), context.requestTime(),
                timeQuery.exchange(), timeQuery.base(), selector.priceType(), interval, timeQuery.includeUnclosed());
        var metadata = new MetricResult.SeriesMetadata(result.requested(), result.effective(),
                result.coverage(), result.interval().code(), interval == null, result.candles().size());
        if (!query.binding().isRangeStatistic()) {
            return new MetricResult.Series(metadata, result.candles().stream()
                    .map(c -> new MetricResult.Sample(Instant.ofEpochMilli(c.openTime()), selector.sample(c))).toList());
        }
        if (!result.coverage().rangeComplete()) {
            return new MetricResult.Omitted(metadata, "区间未完整覆盖，不提供指标 value");
        }
        var change = MarketCalculator.priceChange(result.candles());
        if (change == null || result.coverage().coveredUntilMs() == null) {
            throw new IllegalArgumentException("完整区间缺少价格 K 线或覆盖终点");
        }
        long start = result.candles().get(0).openTime();
        long end = result.coverage().coveredUntilMs();
        return new MetricResult.Scalar(metadata, selector.statistic(change),
                BigDecimal.valueOf(end - start, 3),
                new TimeRange(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), result.effective().timezone()));
    }
}
