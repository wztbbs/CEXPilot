package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MonitorWorkerTest {
    MonitorTestFixture f;
    MonitorPlanRunner runner;
    MonitorWorker worker;
    MonitorWorkManager manager;
    @BeforeEach void setup() {
        f=new MonitorTestFixture(); runner=mock(MonitorPlanRunner.class);
        worker=new MonitorWorker(runner);
        manager=new MonitorWorkManager(f.executions,worker,null,f.clock);
    }
    @AfterEach void close() { f.close(); }
    @Test void deterministicCheckCreatesEventAndOutboxWithoutCallingMarketForPendingOrRepeatedSlot() {
        var draft=f.draft(1); f.run(manager,draft.rule().ruleId()); verifyNoInteractions(runner);
        f.confirm(draft.rule().ruleId());
        when(runner.run(any(MonitorWork.class),any())).thenReturn(f.check(MonitorModels.Result.MATCH));
        f.run(manager,draft.rule().ruleId()); f.run(manager,draft.rule().ruleId());
        verify(runner,times(1)).run(argThat(work->work.slot().equals(MonitorTestFixture.START.minusSeconds(20))),any());
        assertEquals(1,f.count("monitor_evaluation")); assertEquals(1,f.count("monitor_event")); assertEquals(1,f.count("notification_delivery"));
    }
    @Test void marketFailureRetriesSameExecutionAndFailsWithoutNotification() {
        String id=f.active(1).rule().ruleId();
        when(runner.run(any(MonitorWork.class),any())).thenThrow(new IllegalStateException("timeout"));
        f.run(manager,id);
        assertEquals(MonitorExecution.State.FAILED,f.executions.list(id).get(0).status());
        assertEquals(3,f.executions.list(id).get(0).attemptCount());
        assertNull(f.detail(id).latestEvaluation());
        assertEquals(0,f.count("monitor_event"));
        assertEquals(MonitorTestFixture.START.plusSeconds(300),f.detail(id).rule().nextCheckAt());
    }
    @Test void stoppingDuringHttpCallDiscardsResult() {
        String id=f.active(1).rule().ruleId();
        when(runner.run(any(MonitorWork.class),any())).thenAnswer(inv->{
            f.stop(id);
            return f.check(MonitorModels.Result.MATCH);
        });
        f.run(manager,id);
        assertEquals("DELETED",f.detail(id).rule().status());
        assertEquals(0,f.count("monitor_evaluation")); assertEquals(0,f.count("monitor_event"));
    }
    @Test void unsupportedDefinitionFailsWithoutInvokingRunnerOrCreatingAlerts() {
        String id=f.active(1).rule().ruleId();
        f.jdbc.update("UPDATE monitor_rule_version SET rule_spec_json='{}' WHERE rule_id=?",id);
        f.run(manager,id);
        var execution=f.executions.list(id).get(0);
        assertEquals(MonitorExecution.State.FAILED,execution.status()); assertEquals(1,execution.attemptCount());
        assertEquals(0,f.count("monitor_event")); verifyNoInteractions(runner);
    }
}
