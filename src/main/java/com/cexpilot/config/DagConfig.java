package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DAG 运行时配置：Plan 规模上限（节点数 / 深度）、节点执行超时、
 * planner 校验失败后的修复重试次数、层内并行线程数。
 */
@Configuration
@ConfigurationProperties(prefix = "dag")
public class DagConfig {

    /** 单个 Plan 允许的最大节点数（实际生效值为 min(maxNodes, 本轮 maxToolCalls)）。 */
    private int maxNodes = 8;
    /** Plan 拓扑允许的最大深度（最长依赖链层数）。 */
    private int maxDepth = 3;
    /** 单个节点执行超时（毫秒）；超时按节点失败处理，不中断整体。 */
    private long nodeTimeoutMs = 30000;
    /** planner 输出校验失败后的修复重试次数（不含首次调用）。 */
    private int plannerMaxRetries = 2;
    /** 层内并行执行的线程池大小。 */
    private int executorThreads = 4;

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public long getNodeTimeoutMs() {
        return nodeTimeoutMs;
    }

    public void setNodeTimeoutMs(long nodeTimeoutMs) {
        this.nodeTimeoutMs = nodeTimeoutMs;
    }

    public int getPlannerMaxRetries() {
        return plannerMaxRetries;
    }

    public void setPlannerMaxRetries(int plannerMaxRetries) {
        this.plannerMaxRetries = plannerMaxRetries;
    }

    public int getExecutorThreads() {
        return executorThreads;
    }

    public void setExecutorThreads(int executorThreads) {
        this.executorThreads = executorThreads;
    }
}
