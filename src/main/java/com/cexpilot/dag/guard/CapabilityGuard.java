package com.cexpilot.dag.guard;

/**
 * 单个能力护栏：返回 null 表示放行（继续后续护栏），否则返回拒答文案。
 * 新增护栏实现本接口并注册为 Bean（@Order 决定检查顺序）；删除护栏直接删 Bean。
 */
public interface CapabilityGuard {

    String refusal(GuardContext ctx);
}
