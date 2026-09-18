package com.cexpilot.runtime;

/**
 * Trace 出口。Phase 1 用 DB 实现（DbTraceSink），后续可平移到 OpenTelemetry Collector。
 */
public interface TraceSink {

    void record(TraceEvent event);
}
