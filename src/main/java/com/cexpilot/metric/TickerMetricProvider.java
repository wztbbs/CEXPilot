package com.cexpilot.metric;

import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.runtime.RequestContext;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 永续最新成交价与交易所官方滚动 24h 口径指标。
 * 数据时间取交易所快照时间戳，缺失时不猜、不回落到请求时间；成交额为估算值时按缺失处理。
 */
@Component
public class TickerMetricProvider implements MetricProvider {
    public static final String NAME = "ticker";
    private final MarketDataService market;

    public TickerMetricProvider(MarketDataService market) { this.market = market; }
    @Override public String name() { return NAME; }
    @Override public MetricSelector selector(String name) { return TickerMetric.valueOf(name); }

    @Override
    public MetricResult query(MetricQuery query, RequestContext context) {
        Objects.requireNonNull(context, "请求时间上下文不能为空");
        Objects.requireNonNull(context.userZone(), "请求时区不能为空");
        if (!(query instanceof SnapshotQuery snapshot)) {
            throw new IllegalArgumentException("ticker Provider 仅支持快照与官方 24h 查询: " + query.getClass().getSimpleName());
        }
        Ticker ticker = market.ticker(snapshot.exchange(), snapshot.base());
        if (ticker == null) throw new IllegalArgumentException("交易所未返回 24h 行情快照");
        if (ticker.timestamp() <= 0) throw new IllegalArgumentException("快照缺少数据时间，不能标注 as_of");
        var metadata = new MetricResult.SnapshotMetadata(Instant.ofEpochMilli(ticker.timestamp()), context.userZone());
        TickerMetric selector = (TickerMetric) query.binding().selector();
        if (selector.rejectsEstimated() && ticker.quoteVolumeEstimated()) {
            return new MetricResult.Omitted(metadata, "该交易所不提供逐笔成交额，仅有估算值，不提供指标 value");
        }
        BigDecimal value = selector.snapshot(ticker);
        if (value == null) {
            return new MetricResult.Omitted(metadata, "交易所未提供该指标值，不能补零或估算");
        }
        return new MetricResult.Point(metadata, value);
    }
}
