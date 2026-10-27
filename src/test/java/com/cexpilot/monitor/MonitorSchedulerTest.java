package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** scan() 是三步骤的唯一编排点：撤销超时 → 落库排期 → 派发；心跳决定巡检能否发现"定时器已死"。 */
class MonitorSchedulerTest {
    MonitorTestFixture f;
    MonitorRuntimeStatus runtime;
    @BeforeEach void setup() { f=new MonitorTestFixture(); runtime=new MonitorRuntimeStatus(f.clock); }
    @AfterEach void close() { f.close(); }

    @Test void disabledSchedulerStopsBeforeAnyStepAndRecordsNoHeartbeat() {
        f.config.setEnabled(false); f.active(1);
        var manager=mock(MonitorWorkManager.class);
        new MonitorScheduler(f.config,f.repository,f.executions,manager,runtime).scan();
        assertEquals(0,f.count("monitor_execution"));
        verifyNoInteractions(manager);
        var scan=runtime.snapshot().schedule();
        assertNull(scan.lastStartedAt()); assertNull(scan.lastSucceededAt()); assertFalse(scan.running());
    }

    @Test void stepsRunInFixedOrderSoDispatchOnlySeesTheCycleJustPersisted() {
        f.config.setEnabled(true);
        var executions=mock(MonitorExecutionService.class);
        var manager=mock(MonitorWorkManager.class);
        new MonitorScheduler(f.config,f.repository,executions,manager,runtime).scan();
        var order=inOrder(manager,executions);
        order.verify(manager).reapTimeouts();
        order.verify(executions).materializeDue();
        order.verify(manager).dispatch();
    }

    @Test void realScanPersistsTheDueCycleAndMarksTheHeartbeatSuccessful() {
        f.config.setEnabled(true); f.active(1);
        var manager=mock(MonitorWorkManager.class);
        new MonitorScheduler(f.config,f.repository,f.executions,manager,runtime).scan();
        assertEquals(1,f.count("monitor_execution"));
        verify(manager).dispatch();
        var scan=runtime.snapshot().schedule();
        assertNull(scan.lastError());
        assertFalse(scan.running());
        assertEquals(f.clock.instant(),scan.lastStartedAt());
        assertEquals(f.clock.instant(),scan.lastSucceededAt());
    }

    @Test void failureIsRecordedBeforeItPropagatesAndDispatchIsSkipped() {
        f.config.setEnabled(true);
        var executions=mock(MonitorExecutionService.class);
        doThrow(new IllegalStateException("db down")).when(executions).materializeDue();
        var manager=mock(MonitorWorkManager.class);
        var scheduler=new MonitorScheduler(f.config,f.repository,executions,manager,runtime);
        assertThrows(IllegalStateException.class,scheduler::scan);
        var scan=runtime.snapshot().schedule();
        assertEquals("IllegalStateException",scan.lastError());
        assertEquals(f.clock.instant(),scan.lastFailedAt());
        assertNull(scan.lastSucceededAt());
        assertFalse(scan.running());
        verify(manager,never()).dispatch();
    }
}
