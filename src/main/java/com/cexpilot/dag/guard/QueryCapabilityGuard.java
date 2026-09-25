package com.cexpilot.dag.guard;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 任务级能力闸门：按序执行护栏，任一护栏返回文案即拒答。
 * requirements 缺失/非法时直接拒答。新增护栏注册 Bean 并指定 @Order 即可。
 * 具体查询能力由工具及其 service 校验；工具启停由 ToolRegistry 管理。
 */
@Component
public class QueryCapabilityGuard {

    private final List<CapabilityGuard> guards;

    public QueryCapabilityGuard(List<CapabilityGuard> guards) {
        this.guards = guards;
    }

    /** 返回 null 才允许执行；缺失/不认识的语义一律不放行，不让 repair 将能力缺口改成默认值。 */
    public String refusal(GuardContext ctx) {
        if (!ctx.valid()) {
            return GuardMessages.UNCONFIRMED;
        }
        for (CapabilityGuard guard : guards) {
            String refusal = guard.refusal(ctx);
            if (refusal != null) {
                return refusal;
            }
        }
        return null;
    }

    /** 测试用默认链：顺序必须与各护栏 @Order 一致。 */
    public static QueryCapabilityGuard defaults() {
        return new QueryCapabilityGuard(List.of(new PeriodComparisonGuard()));
    }
}
