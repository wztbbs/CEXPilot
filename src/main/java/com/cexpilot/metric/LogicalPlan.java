package com.cexpilot.metric;

import java.util.List;

/**
 * LLM 输出的逻辑计划：指标查询组 + 计算节点。
 * 尚未绑定具体 Provider，也未按交易所展开；由 MetricPlanCompiler 进一步编译为可执行 DAG。
 */
public record LogicalPlan(List<MetricRequest> metrics, List<CalculationRequest> calculations) {

    public LogicalPlan {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        calculations = calculations == null ? List.of() : List.copyOf(calculations);
    }
}
