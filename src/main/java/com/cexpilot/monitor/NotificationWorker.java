package com.cexpilot.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private final MonitorRepository repository;
    private final MonitorConfig config;
    private final EmailNotificationSender sender;
    private final MonitorExecutors executor;
    private final MonitorRuntimeStatus runtime;
    public NotificationWorker(MonitorRepository repository,MonitorConfig config,EmailNotificationSender sender,
                              MonitorExecutors executor) {
        this(repository,config,sender,executor,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public NotificationWorker(MonitorRepository repository,MonitorConfig config,EmailNotificationSender sender,MonitorExecutors executor,MonitorRuntimeStatus runtime) {
        this.repository=repository; this.config=config; this.sender=sender; this.executor=executor; this.runtime=runtime;
    }
    @Scheduled(fixedDelayString="${monitor.mail-scan-ms:10000}")
    public void scan() {
        if (!config.isEnabled()) return;
        if(runtime!=null) runtime.mailStarted();
        try {
            for (String id:repository.dueDeliveries()) {
                try { executor.mail(() -> {
                    try { run(id); } catch (Exception error) { log.error("监控通知执行失败 deliveryId={}",id,error); }
                }); } catch (TaskRejectedException busy) { break; }
            }
            if(runtime!=null) runtime.mailSucceeded();
        } catch(RuntimeException error) { if(runtime!=null) runtime.mailFailed(error); throw error; }
    }
    public void run(String id) {
        var delivery = repository.claimDelivery(id);
        if (delivery==null) return;
        var outcome = sender.send(delivery);
        String status = outcome.status();
        boolean uncertain=delivery.uncertain() || outcome.uncertain();
        if ("RETRY".equals(status)) status=delivery.attemptCount()>=config.getMaxDeliveryAttempts()?(uncertain?"UNKNOWN":"FAILED"):"PENDING";
        if ("FAILED".equals(status) && uncertain) status="UNKNOWN";
        String error=outcome.error();
        if(uncertain && !"SENT".equals(status)) error=MonitorRepository.UNCERTAIN_PREFIX+error;
        repository.finishDelivery(delivery,status,outcome.messageId(),error);
    }
}
