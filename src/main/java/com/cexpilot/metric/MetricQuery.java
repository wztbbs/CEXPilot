package com.cexpilot.metric;

/** 编译完成后的类型化请求；执行时不再反解析 Plan JSON。 */
public sealed interface MetricQuery permits TimeRangeQuery, SnapshotQuery, CountQuery {
    MetricBinding binding();
}
