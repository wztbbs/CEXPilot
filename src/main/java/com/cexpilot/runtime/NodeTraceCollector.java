package com.cexpilot.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点执行期间产生的"子调用"trace 事件（如 metric 节点内部走 MCP 的 tools/call）。
 * 与 HttpRequestCounter 同一模式：DagExecutor 在工作线程进入节点前 bind 本节点的
 * 收集会话，主线程在层间 collect 时冻结并取快照后统一落库，保持"trace 写入只在
 * 主线程"的并发纪律。节点超时被 cancel 后工作线程可能仍存活，迟到写入会被丢弃，
 * 主线程遍历的永远是冻结后的快照（不并发读写同一列表）。
 * 无线程绑定时 record 为空操作（单测、离线路径不受影响）。
 */
public final class NodeTraceCollector {

    /** 一条待落库的子调用事件（不含 traceId，由主线程落库时补上）。 */
    public record Draft(String name, String inputJson, String outputJson, Long durationMs, String error) {
    }

    /** 单个节点的收集会话。 */
    public static final class Session {

        private final List<Draft> events = new ArrayList<>();
        private boolean frozen;

        /** 工作线程写入；冻结后的迟到写入直接丢弃。 */
        synchronized void add(Draft draft) {
            if (!frozen) {
                events.add(draft);
            }
        }

        /** 主线程回收：冻结并返回快照；之后的写入全部丢弃。 */
        public synchronized List<Draft> freezeAndSnapshot() {
            frozen = true;
            return List.copyOf(events);
        }
    }

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private NodeTraceCollector() {
    }

    public static void bind(Session session) {
        CURRENT.set(session);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 节点内的子调用（如一次 MCP tools/call）完成时调用。 */
    public static void record(String name, String inputJson, String outputJson, long durationMs, String error) {
        Session session = CURRENT.get();
        if (session != null) {
            session.add(new Draft(name, inputJson, outputJson, durationMs, error));
        }
    }
}
