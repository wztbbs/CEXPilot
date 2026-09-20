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
    /**
     * planner 调用的结构化输出约束（OpenAI 兼容 response_format）：
     * 空 = 不下发（默认）；json_object = 约束输出合法 JSON 对象；
     * json_schema = 强制输出符合信封 schema（in_domain/intent/reply/plan）。
     * 上线前先对目标模型实测支持情况，不支持会被服务端 400 拒绝。
     */
    private String plannerResponseFormat = "";
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

    public String getPlannerResponseFormat() {
        return plannerResponseFormat;
    }

    public void setPlannerResponseFormat(String plannerResponseFormat) {
        this.plannerResponseFormat = plannerResponseFormat;
    }

    public int getExecutorThreads() {
        return executorThreads;
    }

    public void setExecutorThreads(int executorThreads) {
        this.executorThreads = executorThreads;
    }
}
