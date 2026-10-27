package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static com.cexpilot.monitor.MonitorModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 全程只用 @Scheduled 入口 scan() 驱动：排期 → 领取 → 执行 → 命中 → 告警 → Outbox → 邮件。
 * 不手工调用 materialize/claim/complete，因此编排顺序、心跳和投递队列都在真实路径上。
 */
class MonitorLoopTest {
    MonitorTestFixture f;
    MonitorRuntimeStatus runtime;
    MonitorExecutors pool;
    MonitorWorkManager manager;
    MonitorScheduler scheduler;
    NotificationWorker mailer;
    EmailNotificationSender sender;
    final AtomicReference<Result> next=new AtomicReference<>(Result.MATCH);

    @BeforeEach void setup() {
        f=new MonitorTestFixture(); f.config.setEnabled(true);
        runtime=new MonitorRuntimeStatus(f.clock);
        var worker=mock(MonitorWorker.class);
        when(worker.run(any(),any())).thenAnswer(invocation->f.check(next.get()));
        sender=mock(EmailNotificationSender.class);
        when(sender.isAvailable()).thenReturn(true);
        when(sender.send(any())).thenAnswer(invocation->{
            var delivery=(Delivery)invocation.getArgument(0);
            return new EmailNotificationSender.Outcome("SENT","stub-"+delivery.deliveryId(),null);
        });
        pool=mock(MonitorExecutors.class);
        doAnswer(invocation->{ ((Runnable)invocation.getArgument(0)).run(); return null; }).when(pool).check(any());
        doAnswer(invocation->{ ((Runnable)invocation.getArgument(0)).run(); return null; }).when(pool).mail(any());
        manager=new MonitorWorkManager(f.executions,worker,pool,f.clock);
        scheduler=new MonitorScheduler(f.config,f.repository,f.executions,manager,runtime);
        mailer=new NotificationWorker(f.repository,f.config,sender,pool,runtime);
    }
    @AfterEach void close() { f.close(); }

    void cycle() { scheduler.scan(); mailer.scan(); }
    void cycles(Result result,int count) {
        next.set(result);
        for(int i=0;i<count;i++) { f.clock.advance(300); cycle(); }
    }
    String state(String id) { return f.detail(id).rule().triggerState(); }
    int hits(String id) { return f.detail(id).rule().consecutiveHits(); }
    String deliveryStatus() { return f.jdbc.queryForObject("SELECT status FROM notification_delivery",String.class); }

    @Test void consecutiveHitsAlertOnceThenStaySilentUntilReset() {
        String id=f.active(2).rule().ruleId();
        cycle();
        assertEquals(1,hits(id)); assertEquals("ARMED",state(id)); assertEquals(0,f.count("monitor_event"));
        cycles(Result.MATCH,1);
        assertEquals("WAITING_RESET",state(id)); assertEquals(0,hits(id));
        assertEquals(1,f.count("monitor_event")); assertEquals(1,f.count("notification_delivery"));
        assertEquals("SENT",deliveryStatus(),"告警提交后邮件应经投递队列走完");
        cycles(Result.MATCH,2);
        assertEquals(1,f.count("monitor_event"),"等待复位期间不得重复告警");
        assertEquals(1,f.count("notification_delivery"));
    }

    @Test void aNoMatchRearmsTheRuleAndASecondAlertCanBeDelivered() {
        String id=f.active(1).rule().ruleId();
        cycle();
        assertEquals(1,f.count("monitor_event"));
        cycles(Result.MATCH,1);
        assertEquals(1,f.count("monitor_event"),"未复位前的命中不产生第二条告警");
        cycles(Result.NO_MATCH,1);
        assertEquals("ARMED",state(id));
        cycles(Result.MATCH,1);
        assertEquals(2,f.count("monitor_event")); assertEquals(2,f.count("notification_delivery"));
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE status='SENT'",Integer.class));
    }

    @Test void healthStaysCleanOnALegitimateHitAndResetCycle() {
        String id=f.active(2).rule().ruleId();
        cycle();
        cycles(Result.MATCH,1);
        cycles(Result.MATCH,1);
        cycles(Result.NO_MATCH,1);
        cycles(Result.MATCH,2);
        var health=new MonitorHealthService(f.jdbc,new TransactionTemplate(new DataSourceTransactionManager(f.jdbc.getDataSource())),
                f.config,new MonitorHealthConfig(),runtime,manager,sender,f.clock);
        var report=health.inspect(24,id);
        assertEquals(7,report.ran(),"七项检查都要真的跑过");
        assertEquals(0,report.violationsTotal(),()->report.violations()+" errors="+report.errors());
        assertEquals("HEALTHY",report.status());
    }
}
