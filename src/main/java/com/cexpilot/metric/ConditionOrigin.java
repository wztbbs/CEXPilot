package com.cexpilot.metric;

/**
 * 查询条件的来源标记：EXPLICIT = Planner 声明本轮 set/reset（兼容旧用户原文依据）；INHERITED = 从所选任务版本沿用；
 * DEFAULT = 代码自动补齐（自动粒度、默认 settle、默认 include_unclosed、默认 depth 等）。
 * PLANNED = 模型填写但尚无用户原文依据，不能把它冒充用户的明确要求。
 */
public enum ConditionOrigin {
    EXPLICIT,
    INHERITED,
    DEFAULT,
    PLANNED
}
