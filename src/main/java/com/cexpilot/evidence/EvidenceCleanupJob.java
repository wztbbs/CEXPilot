package com.cexpilot.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 过期 FACT 定时清理（审查 4.4）：retained_until 只在候选筛选时生效，行本身此前
 * 没有任何代码删除，表会无限累积。每小时物理清理一次；清理后引用走 UNAVAILABLE
 * （"原快照不可恢复"澄清）路径——这条降级链路也因此才可能被真实触发（F21）。
 */
@Component
public class EvidenceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCleanupJob.class);

    private final EvidenceStore store;

    public EvidenceCleanupJob(EvidenceStore store) {
        this.store = store;
    }

    /** 启动 10 分钟后首跑，此后每小时一次；失败只记日志，不影响主流程。 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void cleanupExpired() {
        try {
            int deleted = store.deleteExpired(Instant.now());
            if (deleted > 0) {
                log.info("过期 FACT 清理完成 deleted={}", deleted);
            }
        } catch (Exception e) {
            log.warn("过期 FACT 清理失败: {}", e.getMessage());
        }
    }
}
