package com.cexpilot.trace;

import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trace 落库实现。Trace 写入失败不影响主流程，只打日志。
 */
@Component
public class DbTraceSink implements TraceSink {

    private static final Logger log = LoggerFactory.getLogger(DbTraceSink.class);

    private final TraceRepository repository;

    public DbTraceSink(TraceRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(TraceEvent event) {
        try {
            repository.appendEvent(event.traceId(), event);
        } catch (Exception e) {
            log.warn("trace 事件落库失败 traceId={} type={}: {}", event.traceId(), event.eventType(), e.getMessage());
        }
    }
}
