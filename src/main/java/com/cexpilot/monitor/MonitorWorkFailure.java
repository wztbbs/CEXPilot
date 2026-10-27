package com.cexpilot.monitor;

/** 可重试失败和永久失败显式传给 WorkManager；不把条件未命中当成执行失败。 */
public class MonitorWorkFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public MonitorWorkFailure(String code,String message,boolean retryable) { super(message); this.code=code; this.retryable=retryable; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
