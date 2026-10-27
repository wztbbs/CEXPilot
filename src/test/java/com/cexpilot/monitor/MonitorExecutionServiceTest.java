package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.cexpilot.monitor.MonitorModels.*;
import static com.cexpilot.monitor.MonitorExecution.State.*;

class MonitorExecutionServiceTest {
    MonitorTestFixture f;
    @BeforeEach void setup() { f=new MonitorTestFixture(); }
    @AfterEach void close() { f.close(); }
    String active() { return f.active(1).rule().ruleId(); }
    MonitorExecution first(String id) { return f.executions.list(id).get(0); }
    @Test void schedulingPersistsBeforeDispatchAndCursorDoesNotDependOnWorkCompletion() {
        String id=active(); assertEquals(1,f.executions.materialize(id));
        var execution=first(id); assertEquals(PENDING,execution.status()); assertEquals(0,execution.attemptCount());
        assertEquals(0,f.count("monitor_evaluation")); assertEquals(0,f.count("monitor_event"));
        Instant cursor=f.detail(id).rule().nextCheckAt(); assertEquals(0,f.executions.materialize(id));
        var work=f.executions.claim(execution.executionId()); assertEquals(RUNNING,first(id).status());
        assertTrue(f.executions.complete(work,f.check(Result.NO_MATCH)));
        assertEquals(DONE,first(id).status()); assertEquals(cursor,f.detail(id).rule().nextCheckAt());
        assertFalse(f.executions.complete(work,f.check(Result.MATCH))); assertEquals(1,f.count("monitor_evaluation"));
    }
    @Test void executionInsertAndSchedulingCursorRollBackTogether() {
        String id=active(); var cursor=f.detail(id).rule().nextCheckAt();
        var jdbc=spy(f.jdbc);
        doThrow(new IllegalStateException("cursor write failure")).when(jdbc).update(eq("UPDATE monitor_rule SET next_check_at=?,updated_at=? WHERE rule_id=?"),any(),any(),any());
        var service=new MonitorExecutionService(jdbc,f.repository,f.config,f.json,f.clock,f.trigger);
        assertThrows(IllegalStateException.class,()->service.materialize(id));
        assertEquals(0,f.count("monitor_execution")); assertEquals(cursor,f.detail(id).rule().nextCheckAt());
    }
    @Test void stoppedServiceReconcilesMissedSlotsAsExpiredAndRunsOnlyCurrentSlot() {
        String id=active(); f.clock.advance(1800);
        assertEquals(7,f.executions.materialize(id));
        var all=f.executions.list(id); assertEquals(6,all.stream().filter(e->e.status()==FAILED).count());
        assertEquals(PENDING,all.get(0).status()); assertEquals(Instant.parse("2026-10-26T06:30:00Z"),all.get(0).scheduledAt());
        assertEquals(0,f.executions.materialize(id));
    }
    @Test void retryKeepsVersionSlotAndCheckpointWhileTokenChangesAndOldWorkIsFenced() {
        String id=active(); var first=f.claim(id);
        var checkpoint=f.mapper.createObjectNode().put("saved","price-result");
        assertTrue(f.executions.checkpoint(first,checkpoint));
        assertTrue(f.executions.fail(first,new MonitorWorkFailure("NETWORK","temporary",true)));
        assertEquals(PENDING,first(id).status());
        var second=f.executions.claim(first.executionId());
        assertNotEquals(first.workId(),second.workId()); assertEquals(first.executionId(),second.executionId());
        assertEquals(first.slot(),second.slot()); assertEquals(first.version().version(),second.version().version());
        assertEquals(checkpoint,second.execution().checkpoint()); assertTrue(second.rule().leaseToken()>first.rule().leaseToken());
        assertFalse(f.executions.checkpoint(first,f.mapper.createObjectNode().put("stale",true)));
        assertFalse(f.executions.fail(first,new MonitorWorkFailure("OLD","old",true)));
        assertFalse(f.executions.complete(first,f.check(Result.MATCH)));
        assertTrue(f.executions.complete(second,f.check(Result.MATCH))); assertEquals(1,f.count("monitor_event"));
    }
    @Test void restartCanTakeOverTimedOutAttemptWithoutLosingCheckpoint() {
        String id=active(); var first=f.claim(id);
        f.executions.checkpoint(first,f.mapper.createObjectNode().put("saved",1)); f.clock.advance(61);
        var restored=new MonitorExecutionService(f.jdbc,f.repository,f.config,f.json,f.clock,f.trigger);
        var next=restored.claim(first.executionId()); assertNotNull(next);
        assertEquals(2,next.execution().attemptCount()); assertEquals(1,next.execution().checkpoint().path("saved").asInt());
        assertFalse(f.executions.complete(first,f.check(Result.MATCH))); assertTrue(restored.complete(next,f.check(Result.MATCH)));
    }
    @Test void exhaustedPermanentAndExpiredFailuresDoNotCreateAlertOrClearAnotherOwnersLease() {
        String id=active(); var work=f.claim(id);
        for(int attempt=1;attempt<=3;attempt++) {
            assertEquals(attempt,work.execution().attemptCount());
            assertEquals(attempt<3,f.executions.fail(work,new MonitorWorkFailure("NETWORK","down",true)));
            if(attempt<3) work=f.executions.claim(work.executionId());
        }
        assertEquals(FAILED,first(id).status()); assertNull(f.executions.claim(work.executionId()));
        String other=active(); var permanent=f.claim(other);
        assertFalse(f.executions.fail(permanent,new MonitorWorkFailure("INVALID_DEFINITION","bad",false)));
        assertEquals(FAILED,first(other).status()); assertEquals(1,first(other).attemptCount());
        String third=active(); var overdue=f.claim(third); f.clock.advance(300);
        assertNull(f.executions.claim(overdue.executionId())); assertEquals(FAILED,first(third).status());
        assertEquals(0,f.count("monitor_event")); assertEquals(0,f.count("notification_delivery"));
    }
    @Test void competingDispatchersGetOneWorkAndStopCancelsPersistedExecution() throws Exception {
        String id=active(); f.executions.materialize(id); String execution=first(id).executionId();
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<MonitorWork> task=()->{ start.await(); return f.executions.claim(execution); };
            var a=pool.submit(task); var b=pool.submit(task); start.countDown();
            var wa=a.get(3,TimeUnit.SECONDS); var wb=b.get(3,TimeUnit.SECONDS); assertTrue((wa==null)!=(wb==null));
            var work=wa==null?wb:wa; f.stop(id);
            assertEquals(CANCELLED,first(id).status());
            assertFalse(f.executions.checkpoint(work,f.mapper.createObjectNode())); assertFalse(f.executions.complete(work,f.check(Result.MATCH)));
        } finally { pool.shutdownNow(); }
    }
    @Test void evaluationAlreadyCommittedReconcilesExecutionWithoutAnotherWork() {
        String id=active(); f.executions.materialize(id); var execution=first(id);
        var now=MonitorRepository.utc(f.clock.instant());
        f.jdbc.update("INSERT INTO monitor_evaluation (evaluation_id,rule_id,rule_version,scheduled_at,data_cutoff_at,started_at,finished_at,result,evidence_json) VALUES (?, ?,1,?,?,?,?, 'NO_MATCH','{}')",
                UUID.randomUUID().toString(),id,MonitorRepository.utc(execution.scheduledAt()),MonitorRepository.utc(execution.scheduledAt()),now,now);
        assertNull(f.executions.claim(execution.executionId())); assertEquals(DONE,first(id).status());
        assertEquals("NO_MATCH",first(id).result()); assertEquals(0,first(id).attemptCount());
    }
    @Test void terminalFailureBreaksHitsButDoesNotRearmAnAlreadyTriggeredMonitor() {
        String id=f.active(2).rule().ruleId(); f.evaluate(id,Result.MATCH);
        assertEquals(1,f.detail(id).rule().consecutiveHits()); f.clock.advance(300);
        var work=f.claim(id); f.executions.fail(work,new MonitorWorkFailure("INVALID","bad",false));
        assertEquals(0,f.detail(id).rule().consecutiveHits());
        String fired=f.active(1).rule().ruleId(); f.evaluate(fired,Result.MATCH); f.clock.advance(300);
        f.executions.fail(f.claim(fired),new MonitorWorkFailure("INVALID","bad",false));
        assertEquals("WAITING_RESET",f.detail(fired).rule().triggerState());
    }
    @Test void versionSwitchCancelsUnstartedExecutionAndResultsRollbackWithOutbox() {
        String id=active(); f.executions.materialize(id); String execution=first(id).executionId();
        f.jdbc.update("UPDATE monitor_rule SET active_version=2 WHERE rule_id=?",id);
        assertNull(f.executions.claim(execution)); assertEquals(CANCELLED,first(id).status());
        String other=active(); var work=f.claim(other); f.jdbc.update("UPDATE monitor_rule SET recipient=NULL WHERE rule_id=?",other);
        assertThrows(RuntimeException.class,()->f.executions.complete(work,f.check(Result.MATCH)));
        assertEquals(RUNNING,first(other).status()); assertEquals(0,f.count("monitor_evaluation")); assertEquals(0,f.count("monitor_event"));
        f.jdbc.update("UPDATE monitor_rule SET recipient='pilot@example.com' WHERE rule_id=?",other); assertTrue(f.executions.complete(work,f.check(Result.MATCH)));
        assertEquals(DONE,first(other).status()); assertEquals(1,f.count("notification_delivery"));
    }
}
