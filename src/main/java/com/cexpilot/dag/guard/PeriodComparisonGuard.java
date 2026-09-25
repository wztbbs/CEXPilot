package com.cexpilot.dag.guard;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 跨期比较需要聚合算子；两段数据可查询并不代表整个比较任务已经受支持。 */
@Component
@Order(1)
public class PeriodComparisonGuard implements CapabilityGuard {
    @Override
    public String refusal(GuardContext ctx) {
        return ctx.requiresPeriodComparison() ? GuardMessages.UNSUPPORTED_COMPARISON : null;
    }
}
