package com.cexpilot.market;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本轮问答真实发出的交易所 HTTP 请求计数（阶段三观测）。
 * 一个 metric 节点可能分页/重试多次请求，节点数不等于 HTTP 次数；
 * 由 DagExecutor 在工作线程进入节点执行前 bind 本轮共享计数器，
 * BinanceClient/OkxClient 的 get 每次真实发起请求前 increment。
 * 无线程绑定时 increment 为空操作（单测、离线解析路径不受影响）。
 */
public final class HttpRequestCounter {

    private static final ThreadLocal<AtomicInteger> CURRENT = new ThreadLocal<>();

    private HttpRequestCounter() {
    }

    public static void bind(AtomicInteger counter) {
        CURRENT.set(counter);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 每真实发起一次 HTTP 请求（含重试的每次尝试）调用一次。 */
    public static void increment() {
        AtomicInteger counter = CURRENT.get();
        if (counter != null) {
            counter.incrementAndGet();
        }
    }
}
