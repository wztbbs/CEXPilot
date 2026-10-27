package com.cexpilot.monitor;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** 包装私有线程池，避免注册 Executor Bean 导致 Boot 撤销既有 applicationTaskExecutor。 */
@Component
public class MonitorExecutors {
    private final ThreadPoolTaskExecutor checks = pool("monitor-check-",2);
    private final ThreadPoolTaskExecutor mail = pool("monitor-mail-",1);
    private static ThreadPoolTaskExecutor pool(String prefix,int size) {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(size); pool.setMaxPoolSize(size); pool.setQueueCapacity(0);
        pool.setThreadNamePrefix(prefix);
        pool.setWaitForTasksToCompleteOnShutdown(true); pool.setAwaitTerminationSeconds(30);
        pool.initialize(); return pool;
    }
    public void check(Runnable work) { checks.execute(work); }
    public void mail(Runnable work) { mail.execute(work); }
    @PreDestroy public void shutdown() { checks.shutdown(); mail.shutdown(); }
}
