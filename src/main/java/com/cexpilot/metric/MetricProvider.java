package com.cexpilot.metric;

import com.cexpilot.runtime.RequestContext;

/** 指标取数实现与计算 Tool 分离；不接收或返回旧 Tool JSON。 */
public interface MetricProvider {
    String name();
    /** 按名解析选择器，非法名抛 IllegalArgumentException。 */
    MetricSelector selector(String name);
    MetricResult query(MetricQuery query, RequestContext context);
}
