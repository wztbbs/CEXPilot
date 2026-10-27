package com.cexpilot.monitor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorRuntimeStatusTest {
    @Test void schedulerRecordsSuccessAndFailureAndDisabledScanDoesNotPretendToRun() {
        var clock=new MonitorTestFixture.MutableClock(MonitorTestFixture.START);
        var runtime=new MonitorRuntimeStatus(clock); var config=new MonitorConfig();
        var executions=mock(MonitorExecutionService.class); var manager=mock(MonitorWorkManager.class);
        var scheduler=new MonitorScheduler(config,mock(MonitorRepository.class),executions,manager,runtime);
        scheduler.scan(); assertNull(runtime.snapshot().schedule().lastStartedAt()); verifyNoInteractions(executions,manager);
        config.setEnabled(true); scheduler.scan(); assertEquals(clock.instant(),runtime.snapshot().schedule().lastSucceededAt());
        clock.advance(10); doThrow(new IllegalStateException("private failure")).when(executions).materializeDue();
        assertThrows(IllegalStateException.class,scheduler::scan);
        var scan=runtime.snapshot().schedule(); assertEquals("IllegalStateException",scan.lastError());
        assertFalse(scan.running()); assertEquals(clock.instant(),scan.lastFailedAt());
        assertEquals(clock.instant().minusSeconds(10),scan.lastSucceededAt());
    }
    @Test void mailScanRecordsOnlySuccessfulQueueReadAndSubmissionAsSuccess() {
        var clock=new MonitorTestFixture.MutableClock(MonitorTestFixture.START);
        var runtime=new MonitorRuntimeStatus(clock); var config=new MonitorConfig(); config.setEnabled(true);
        var repository=mock(MonitorRepository.class);
        var worker=new NotificationWorker(repository,config,mock(EmailNotificationSender.class),mock(MonitorExecutors.class),runtime);
        worker.scan(); assertEquals(clock.instant(),runtime.snapshot().mail().lastSucceededAt());
        clock.advance(10); when(repository.dueDeliveries()).thenThrow(new IllegalStateException("SQL detail"));
        assertThrows(IllegalStateException.class,worker::scan);
        assertEquals(clock.instant(),runtime.snapshot().mail().lastFailedAt()); assertFalse(runtime.snapshot().mail().running());
        assertEquals(clock.instant().minusSeconds(10),runtime.snapshot().mail().lastSucceededAt());
    }
}
