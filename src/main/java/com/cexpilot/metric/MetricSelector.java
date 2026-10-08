package com.cexpilot.metric;

/** 按名解析的指标选择器；实现可以是枚举，也可以是配置驱动。 */
public interface MetricSelector {
    String name();
    boolean supports(String shape);
}
