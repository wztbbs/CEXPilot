package com.cexpilot.dag.guard;

/** 护栏共享的拒答文案。 */
public final class GuardMessages {

    public static final String UNCONFIRMED = "暂时无法可靠确认本次任务是否需要跨期比较，无法处理本次查询。";
    public static final String UNSUPPORTED_COMPARISON = "暂未支持跨期比较所需的聚合计算，无法完成本次比较；不能仅查询部分数据后冒充比较结果。";

    private GuardMessages() {}
}
