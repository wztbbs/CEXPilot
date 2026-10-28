package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.monitor.MonitorModels.*;

class MonitorRepositoryTest {
    MonitorTestFixture f;
    @BeforeEach void setup() { f=new MonitorTestFixture(); }
    @AfterEach void close() { f.close(); }
    @Test void consecutiveHitsFireOnceAndRearmOnlyOnValidNoMatch() {
        String id=f.active(2).rule().ruleId();
        f.evaluate(id,Result.MATCH); assertEquals(0,f.count("monitor_event"));
        f.clock.advance(300); f.evaluate(id,Result.MATCH);
        assertEquals(1,f.count("monitor_event")); assertEquals(1,f.count("notification_delivery"));
        assertEquals(2,f.repository.events(id).get(0).path("evidence").path("checks").size());
        for(int i=0;i<10;i++) { f.clock.advance(300); f.evaluate(id,Result.MATCH); }
        assertEquals(1,f.count("monitor_event"));
        f.clock.advance(300); f.evaluate(id,Result.UNKNOWN);
        assertEquals("WAITING_RESET",f.detail(id).rule().triggerState());
        f.clock.advance(300); f.evaluate(id,Result.MATCH); assertEquals(1,f.count("monitor_event"));
        f.clock.advance(300); f.evaluate(id,Result.NO_MATCH);
        f.clock.advance(300); f.evaluate(id,Result.MATCH);
        f.clock.advance(300); f.evaluate(id,Result.MATCH);
        assertEquals(2,f.count("monitor_event"));
    }
    @Test void unknownAndMissedSlotBreakConsecutiveHits() {
        String id=f.active(2).rule().ruleId();
        f.evaluate(id,Result.MATCH);
        f.clock.advance(300); f.evaluate(id,Result.UNKNOWN);
        f.clock.advance(300); f.evaluate(id,Result.MATCH); assertEquals(0,f.count("monitor_event"));
        f.clock.advance(900); f.evaluate(id,Result.MATCH); assertEquals(0,f.count("monitor_event"));
        assertEquals(1,f.detail(id).rule().consecutiveHits());
        f.clock.advance(300); f.evaluate(id,Result.MATCH); assertEquals(1,f.count("monitor_event"));
    }
    @Test void repeatedCommitAndCompetingClaimsCannotCountTwice() throws Exception {
        String id=f.active(1).rule().ruleId();
        var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
        try {
            Callable<MonitorWork> task=()->{ gate.await(); return f.claim(id); };
            var a=pool.submit(task); var b=pool.submit(task); gate.countDown();
            MonitorWork ca=a.get(5,TimeUnit.SECONDS), cb=b.get(5,TimeUnit.SECONDS);
            assertTrue((ca==null)!=(cb==null));
            MonitorWork claim=ca==null?cb:ca;
            assertTrue(f.executions.complete(claim,f.check(Result.MATCH)));
            assertFalse(f.executions.complete(claim,f.check(Result.MATCH)));
            assertNull(f.claim(id));
            assertEquals(1,f.count("monitor_evaluation")); assertEquals(1,f.count("monitor_event"));
        } finally { pool.shutdownNow(); }
    }
    @Test void stoppedAndExpiredWorkersCannotCreateEvents() {
        String id=f.active(1).rule().ruleId();
        var old=f.claim(id); f.clock.advance(121);
        assertFalse(f.executions.complete(old,f.check(Result.MATCH)));
        var takeover=f.claim(id); assertNotNull(takeover);
        assertTrue(takeover.execution().leaseToken()>old.execution().leaseToken());
        assertFalse(f.executions.complete(old,f.check(Result.MATCH)));
        f.stop(id);
        assertFalse(f.executions.complete(takeover,f.check(Result.MATCH)));
        assertEquals(0,f.count("monitor_evaluation")); assertEquals(0,f.count("monitor_event"));
    }
    @Test void versionChangeInvalidatesOldExecution() {
        String id=f.active(1).rule().ruleId(); var old=f.claim(id);
        f.jdbc.update("UPDATE monitor_rule SET active_version=2 WHERE rule_id=?",id);
        assertFalse(f.executions.complete(old,f.check(Result.MATCH)));
        assertEquals(0,f.count("monitor_event"));
    }
    @Test void eventOutboxFailureRollsBackEvaluationAndTriggerState() {
        String id=f.active(1).rule().ruleId(); var claim=f.claim(id);
        f.jdbc.update("UPDATE monitor_rule SET recipient=NULL WHERE rule_id=?",id); // NOT NULL 失败发生在 Event 写入之后
        assertThrows(RuntimeException.class,()->f.executions.complete(claim,f.check(Result.MATCH)));
        assertEquals(0,f.count("monitor_evaluation")); assertEquals(0,f.count("monitor_event")); assertEquals(0,f.count("notification_delivery"));
        assertEquals("ARMED",f.detail(id).rule().triggerState());
        f.jdbc.update("UPDATE monitor_rule SET recipient='pilot@example.com' WHERE rule_id=?",id);
        assertTrue(f.executions.complete(claim,f.check(Result.MATCH)));
        assertEquals(1,f.count("monitor_event"));
    }
    @Test void restartResumesLatestSlotAndSnapshotsSurviveEvaluationCleanup() {
        String id=f.active(1).rule().ruleId();
        f.clock.advance(1800);
        var recreated=new MonitorRepository(f.jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.jdbc.getDataSource()),f.json,f.config,f.clock);
        var restored=new MonitorExecutionService(f.jdbc,recreated,f.config,f.json,f.clock,f.trigger);
        restored.materialize(id); var claim=restored.claim(restored.list(id).get(0).executionId());
        assertEquals(Instant.parse("2026-10-26T06:30:00Z"),claim.slot());
        assertTrue(restored.complete(claim,f.check(Result.MATCH)));
        f.clock.advance(8*86400L); assertEquals(1,recreated.cleanupEvaluations());
        assertEquals(0,f.count("monitor_evaluation"));
        assertEquals(1,f.repository.events(id).get(0).path("evidence").path("checks").size());
    }
    @Test void oldUnattemptedOutboxFreezesOnceButOldAttemptedDeliveryNeedsManualReview() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        var event=f.repository.events(id).get(0); String eventId=event.path("eventId").asText();
        var old=((com.fasterxml.jackson.databind.node.ObjectNode)event.path("evidence")).deepCopy(); old.remove("resend_request");
        f.jdbc.update("UPDATE monitor_event SET evidence_snapshot=? WHERE event_id=?",f.json.write(old),eventId);
        String deliveryId=f.repository.dueDeliveries().get(0);
        var claimed=f.repository.claimDelivery(deliveryId);
        assertEquals("monitor@example.com",claimed.snapshot().at("/resend_request/from").asText());
        assertTrue(claimed.snapshot().has("checks"));
        f.repository.finishDelivery(claimed,"PENDING",null,"old-unsent-error");
        f.jdbc.update("UPDATE monitor_event SET evidence_snapshot=? WHERE event_id=?",f.json.write(old),eventId);
        f.clock.advance(300);
        assertNull(f.repository.claimDelivery(deliveryId));
        assertEquals("UNKNOWN",f.repository.events(id).get(0).path("deliveryStatus").asText());
    }
    @Test void pastWindowQueuedMessageIsNotSubmittedAndOldInFlightMessageDoesNotSwitchTransport() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        String deliveryId=f.repository.dueDeliveries().get(0);
        f.clock.advance(23*3600); assertNull(f.repository.claimDelivery(deliveryId));
        assertEquals("FAILED",f.repository.events(id).get(0).path("deliveryStatus").asText());
        String other=f.active(1).rule().ruleId(); f.evaluate(other,Result.MATCH);
        String otherDelivery=f.repository.dueDeliveries().get(0);
        var claim=f.repository.claimDelivery(otherDelivery);
        var legacy=((com.fasterxml.jackson.databind.node.ObjectNode)claim.snapshot()).deepCopy(); legacy.remove("resend_request");
        f.jdbc.update("UPDATE monitor_event SET evidence_snapshot=? WHERE event_id=?",f.json.write(legacy),claim.eventId());
        f.clock.advance(121); assertNull(f.repository.claimDelivery(otherDelivery));
        assertEquals("UNKNOWN",f.repository.events(other).get(0).path("deliveryStatus").asText());
        f.repository.finishDelivery(claim,"SENT","stale-id",null);
        assertEquals("UNKNOWN",f.repository.events(other).get(0).path("deliveryStatus").asText());
    }
    @Test void expiredResendSendingRecoversWithFencingAndSamePayload() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        String deliveryId=f.repository.dueDeliveries().get(0);
        var delivery=f.repository.claimDelivery(deliveryId); assertNotNull(delivery);
        assertNull(f.repository.claimDelivery(deliveryId));
        f.clock.advance(121); assertNull(f.repository.claimDelivery(deliveryId));
        f.repository.finishDelivery(delivery,"SENT","late-id",null);
        assertEquals("PENDING",f.repository.events(id).get(0).path("deliveryStatus").asText());
        assertEquals(java.util.List.of(deliveryId),f.repository.dueDeliveries());
        var resumed=f.repository.claimDelivery(deliveryId);
        assertTrue(resumed.uncertain()); assertEquals(delivery.snapshot(),resumed.snapshot());
        assertTrue(resumed.leaseToken()>delivery.leaseToken());
        f.repository.finishDelivery(resumed,"SENT","resend-id",null);
        assertEquals("SENT",f.repository.events(id).get(0).path("deliveryStatus").asText());
    }
}
