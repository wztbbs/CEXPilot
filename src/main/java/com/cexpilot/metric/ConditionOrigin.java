package com.cexpilot.metric;

/**
 * 查询条件的来源标记：EXPLICIT = 用户/Planner 明确指定；INHERITED = 从上轮 frame 沿用（阶段一(c) 才产生）；
 * DEFAULT = 代码自动补齐（自动粒度、默认 settle、默认 include_unclosed、默认 depth 等）。
 */
public enum ConditionOrigin {
    EXPLICIT,
    INHERITED,
    DEFAULT
}
