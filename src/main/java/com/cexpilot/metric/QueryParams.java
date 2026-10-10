package com.cexpilot.metric;

/**
 * 指标查询形态对应的参数；按形态拆分为互斥子类型，避免一个对象里同时挂着 time/count/depth。
 */
public sealed interface QueryParams
        permits TimeQueryParams, CountQueryParams, SnapshotQueryParams {
}
