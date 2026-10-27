package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.cexpilot.monitor.MonitorModels.*;

class NotificationWorkerTest {
    MonitorTestFixture f;
    @BeforeEach void setup() { f=new MonitorTestFixture(); }
    @AfterEach void close() { f.close(); }
    @Test void workerRetriesOnlyKnownUnsentFailuresAndStopsAtLimit() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        String deliveryId=f.repository.dueDeliveries().get(0);
        var sender=mock(EmailNotificationSender.class);
        when(sender.send(any())).thenReturn(new EmailNotificationSender.Outcome("RETRY",null,"not sent"));
        var worker=new NotificationWorker(f.repository,f.config,sender,null);
        for(int i=0;i<3;i++) { worker.run(deliveryId); f.clock.advance(600); }
        assertEquals("FAILED",f.repository.events(id).get(0).path("deliveryStatus").asText());
        worker.run(deliveryId); verify(sender,times(3)).send(any());
    }
    @Test void successAndUnknownAreNeverAutomaticallySentAgain() {
        for(String outcome:new String[]{"SENT","UNKNOWN"}) {
            String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
            String deliveryId=f.repository.dueDeliveries().get(0);
            var sender=mock(EmailNotificationSender.class);
            when(sender.send(any())).thenReturn(new EmailNotificationSender.Outcome(outcome,"msg",null));
            var worker=new NotificationWorker(f.repository,f.config,sender,null);
            worker.run(deliveryId); worker.run(deliveryId);
            assertEquals(outcome,f.repository.events(id).get(0).path("deliveryStatus").asText());
            verify(sender,times(1)).send(any());
        }
    }
    @Test void ambiguousRetriesStopAsUnknownAndRemainStickyAcrossLaterKnownRejection() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        String deliveryId=f.repository.dueDeliveries().get(0);
        var sender=mock(EmailNotificationSender.class);
        when(sender.send(any())).thenReturn(new EmailNotificationSender.Outcome("RETRY",null,"response lost",true),
                new EmailNotificationSender.Outcome("FAILED",null,"unauthorized"));
        var worker=new NotificationWorker(f.repository,f.config,sender,null);
        worker.run(deliveryId); f.clock.advance(600); worker.run(deliveryId);
        assertEquals("UNKNOWN",f.repository.events(id).get(0).path("deliveryStatus").asText());
        worker.run(deliveryId); verify(sender,times(2)).send(any());
    }
    @Test void ambiguousRetryExhaustionDoesNotClaimKnownFailure() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        String deliveryId=f.repository.dueDeliveries().get(0);
        var sender=mock(EmailNotificationSender.class);
        when(sender.send(any())).thenReturn(new EmailNotificationSender.Outcome("RETRY",null,"unavailable",true));
        var worker=new NotificationWorker(f.repository,f.config,sender,null);
        for(int i=0;i<3;i++) { worker.run(deliveryId); f.clock.advance(600); }
        assertEquals("UNKNOWN",f.repository.events(id).get(0).path("deliveryStatus").asText());
    }
    /** 定时入口本身：run(id) 的用例覆盖不到 dueDeliveries → 投递池 → 回写 这一段。 */
    @Test void scanTakesDueOutboxThroughTheMailPoolAndRecordsSuccess() {
        f.config.setEnabled(true);
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        var sender=mock(EmailNotificationSender.class);
        when(sender.send(any())).thenReturn(new EmailNotificationSender.Outcome("SENT","stub-msg-1",null));
        var pool=mock(MonitorExecutors.class);
        doAnswer(inv->{ ((Runnable)inv.getArgument(0)).run(); return null; }).when(pool).mail(any());
        var runtime=new MonitorRuntimeStatus(f.clock);
        new NotificationWorker(f.repository,f.config,sender,pool,runtime).scan();
        assertEquals("SENT",f.repository.events(id).get(0).path("deliveryStatus").asText());
        assertEquals("stub-msg-1",f.jdbc.queryForObject("SELECT provider_message_id FROM notification_delivery",String.class));
        assertTrue(f.repository.dueDeliveries().isEmpty());
        assertEquals(f.clock.instant(),runtime.snapshot().mail().lastSucceededAt());
        assertNull(runtime.snapshot().mail().lastError());
    }
    @Test void fullMailPoolLeavesTheOutboxPendingForTheNextScanInsteadOfLosingIt() {
        f.config.setEnabled(true);
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        var sender=mock(EmailNotificationSender.class);
        var pool=mock(MonitorExecutors.class);
        doThrow(new org.springframework.core.task.TaskRejectedException("full")).when(pool).mail(any());
        var runtime=new MonitorRuntimeStatus(f.clock);
        var worker=new NotificationWorker(f.repository,f.config,sender,pool,runtime);
        assertDoesNotThrow(worker::scan);
        assertEquals("PENDING",f.repository.events(id).get(0).path("deliveryStatus").asText());
        assertEquals(1,f.repository.dueDeliveries().size());
        verifyNoInteractions(sender);
        assertNotNull(runtime.snapshot().mail().lastSucceededAt());
    }
    @Test void failedOutboxQueryIsRecordedAndRethrownSoTheScanCannotLookHealthy() {
        f.config.setEnabled(true);
        var sender=mock(EmailNotificationSender.class);
        var runtime=new MonitorRuntimeStatus(f.clock);
        f.jdbc.execute("DROP TABLE notification_delivery");
        var worker=new NotificationWorker(f.repository,f.config,sender,null,runtime);
        assertThrows(RuntimeException.class,worker::scan);
        var mail=runtime.snapshot().mail();
        assertNotNull(mail.lastError()); assertNull(mail.lastSucceededAt()); assertFalse(mail.running());
    }
}
