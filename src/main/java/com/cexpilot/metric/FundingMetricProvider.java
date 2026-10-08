package com.cexpilot.metric;

import com.cexpilot.market.funding.FundingQueryService;
import com.cexpilot.market.funding.FundingRecentResult;
import com.cexpilot.runtime.RequestContext;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Objects;

/** 已结算资金费率：按结算期取样（count 模式），不做区间统计、不跑覆盖核对。 */
@Component
public class FundingMetricProvider implements MetricProvider {
    public static final String NAME = "funding";
    private final FundingQueryService service;

    public FundingMetricProvider(FundingQueryService service) { this.service = service; }
    @Override public String name() { return NAME; }
    @Override public MetricSelector selector(String name) { return FundingMetric.valueOf(name); }

    @Override
    public MetricResult query(MetricQuery query, RequestContext context) {
        Objects.requireNonNull(context, "请求时间上下文不能为空");
        Objects.requireNonNull(context.requestTime(), "请求时间不能为空");
        Objects.requireNonNull(context.userZone(), "请求时区不能为空");
        if (!(query instanceof CountQuery count)) {
            throw new IllegalArgumentException("funding Provider 仅支持最近 N 期查询: " + query.getClass().getSimpleName());
        }
        FundingRecentResult result = service.queryRecent(count.exchange(), count.base(), count.count(),
                context.requestTime());
        if (result == null) throw new IllegalArgumentException("资金费率取样失败");
        if (result.points().isEmpty()) {
            return new MetricResult.Omitted(new MetricResult.RecentMetadata(count.count(), 0, false,
                    result.singlePeriodMs(), context.userZone()), "未取到已结算费率样本，不提供指标 value");
        }
        var metadata = new MetricResult.RecentMetadata(count.count(), result.points().size(),
                result.points().size() == count.count(), result.singlePeriodMs(), context.userZone());
        FundingMetric selector = (FundingMetric) query.binding().selector();
        return new MetricResult.Series(metadata, result.points().stream()
                .map(point -> new MetricResult.Sample(Instant.ofEpochMilli(point.fundingTime()),
                        selector.sample(point))).toList());
    }
}
