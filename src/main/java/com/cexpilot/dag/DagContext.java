package com.cexpilot.dag;

import com.cexpilot.runtime.ToolResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * nodeId → ToolResult 的并发安全容器。
 * 并发纪律（沿用 merchant 项目的约定）：工作线程只读，写入统一由主线程在层间屏障完成，
 * 因此同层节点读到的永远是之前层级的稳定结果。
 */
public class DagContext {

    private final Map<String, ToolResult> results = new ConcurrentHashMap<>();

    public ToolResult get(String nodeId) {
        return results.get(nodeId);
    }

    public void put(String nodeId, ToolResult result) {
        results.put(nodeId, result);
    }
}
