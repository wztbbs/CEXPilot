package com.cexpilot.monitor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MonitorScheduler {
    private final MonitorConfig config;
    private final MonitorRepository repository;
    private final MonitorExecutionService executions;
    private final MonitorWorkManager manager;
    private final MonitorRuntimeStatus runtime;
    public MonitorScheduler(MonitorConfig config,MonitorRepository repository,MonitorExecutionService executions,MonitorWorkManager manager,MonitorRuntimeStatus runtime) {
        this.config=config; this.repository=repository; this.executions=executions; this.manager=manager; this.runtime=runtime;
    }
    @Scheduled(fixedDelayString="${monitor.scan-ms:10000}")
    public void scan() {
        if(!config.isEnabled()) return;
        runtime.scheduleStarted();
        try {
            manager.reapTimeouts();
            executions.materializeDue(); // 先持久化应执行列表，线程池满不会丢失周期
            manager.dispatch();
            runtime.scheduleSucceeded();
        } catch(RuntimeException error) { runtime.scheduleFailed(error); throw error; }
    }
    @Scheduled(fixedDelay=3600000,initialDelay=600000)
    public void cleanup() { if(config.isEnabled()) { repository.cleanupEvaluations(); executions.cleanup(); } }
}
