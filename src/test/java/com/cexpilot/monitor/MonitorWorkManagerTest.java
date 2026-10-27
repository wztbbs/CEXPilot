package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import org.springframework.core.task.TaskRejectedException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.cexpilot.monitor.MonitorModels.*;
import static com.cexpilot.monitor.MonitorExecution.State.*;

class MonitorWorkManagerTest {
    MonitorTestFixture f;
    @BeforeEach void setup() { f=new MonitorTestFixture(); }
    @AfterEach void close() { f.close(); }
    String execution() { String rule=f.active(1).rule().ruleId(); f.executions.materialize(rule); return f.executions.list(rule).get(0).executionId(); }
    @Test void retryUsesNewWorkAndRestoresSavedCheckpointBeforeCommittingExactlyOnce() {
        String id=execution(); var worker=mock(MonitorWorker.class); var works=new ArrayList<MonitorWork>();
        when(worker.run(any(),any())).thenAnswer(inv->{
            MonitorWork work=inv.getArgument(0); works.add(work); Consumer<JsonNode> save=inv.getArgument(1);
            if(works.size()==1) { save.accept(f.mapper.createObjectNode().put("price",42)); throw new MonitorWorkFailure("OI_UNAVAILABLE","retry",true); }
            assertEquals(42,work.execution().checkpoint().path("price").asInt()); return f.check(Result.MATCH);
        });
        var manager=new MonitorWorkManager(f.executions,worker,null,f.clock); manager.runNow(id); manager.runNow(id);
        assertEquals(2,works.size()); assertNotEquals(works.get(0).workId(),works.get(1).workId());
        assertEquals(works.get(0).slot(),works.get(1).slot()); assertEquals(DONE,f.executions.find(id).status());
        assertEquals(1,f.count("monitor_evaluation")); assertEquals(1,f.count("monitor_event")); assertEquals(1,f.count("notification_delivery"));
        assertTrue(manager.activeWorks().isEmpty());
    }
    @Test void timeoutDetectedAtCheckpointCanRetryEvenBeforeWatchdogScan() {
        String id=execution(); var worker=mock(MonitorWorker.class); var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(worker.run(any(),any())).thenAnswer(inv->{
            if(calls.incrementAndGet()==1) { f.clock.advance(61); Consumer<JsonNode> save=inv.getArgument(1); save.accept(f.mapper.createObjectNode()); }
            return f.check(Result.NO_MATCH);
        });
        new MonitorWorkManager(f.executions,worker,null,f.clock).runNow(id);
        assertEquals(DONE,f.executions.find(id).status()); assertEquals(2,f.executions.find(id).attemptCount());
    }
    @Test void rejectedThreadPoolLeavesDurablePendingExecutionUnclaimed() {
        String id=execution(); var worker=mock(MonitorWorker.class); var pool=mock(MonitorExecutors.class);
        doThrow(new TaskRejectedException("full")).when(pool).check(any());
        var manager=new MonitorWorkManager(f.executions,worker,pool,f.clock); manager.dispatch();
        assertEquals(PENDING,f.executions.find(id).status()); assertEquals(0,f.executions.find(id).attemptCount());
        verifyNoInteractions(worker);
        doAnswer(inv->{ ((Runnable)inv.getArgument(0)).run(); return null; }).when(pool).check(any());
        when(worker.run(any(),any())).thenReturn(f.check(Result.NO_MATCH)); manager.dispatch();
        assertEquals(DONE,f.executions.find(id).status());
    }
    @Test void timeoutRevokesOldWorkAndLateCleanupCannotRemoveItsReplacement() throws Exception {
        String id=execution(); var worker=mock(MonitorWorker.class); var pool=new MonitorExecutors();
        var firstEntered=new CountDownLatch(1); var firstRelease=new CountDownLatch(1);
        var secondEntered=new CountDownLatch(1); var secondRelease=new CountDownLatch(1); var firstExited=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(worker.run(any(),any())).thenAnswer(inv->{
            if(calls.incrementAndGet()==1) {
                firstEntered.countDown();
                boolean released=false; while(!released) { try { released=firstRelease.await(3,TimeUnit.SECONDS); } catch(InterruptedException ignored) {} }
                try { Consumer<JsonNode> save=inv.getArgument(1); save.accept(f.mapper.createObjectNode().put("late",true)); }
                finally { firstExited.countDown(); }
            } else { secondEntered.countDown(); assertTrue(secondRelease.await(3,TimeUnit.SECONDS)); }
            return f.check(Result.MATCH);
        });
        var manager=new MonitorWorkManager(f.executions,worker,pool,f.clock);
        try {
            manager.dispatch(); assertTrue(firstEntered.await(3,TimeUnit.SECONDS));
            manager.dispatch(); assertEquals(1,calls.get());
            f.clock.advance(61); manager.reapTimeouts(); manager.dispatch(); assertTrue(secondEntered.await(3,TimeUnit.SECONDS));
            firstRelease.countDown(); assertTrue(firstExited.await(3,TimeUnit.SECONDS));
            assertEquals(1,manager.activeWorks().size()); assertEquals(2,manager.activeWorks().get(0).execution().attemptCount());
            secondRelease.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(f.executions.find(id).status()!=DONE && System.nanoTime()<end) Thread.sleep(5);
            assertEquals(DONE,f.executions.find(id).status()); assertEquals(1,f.count("monitor_event"));
            assertFalse(f.executions.find(id).checkpoint().has("late"));
        } finally { firstRelease.countDown(); secondRelease.countDown(); pool.shutdown(); }
    }
}
