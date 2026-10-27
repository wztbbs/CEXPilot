package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static com.cexpilot.monitor.MonitorModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真 SQL 检查不变量，只用本地 H2；包括查询失败、限量与跨访客场景。 */
class MonitorHealthServiceTest {
    MonitorTestFixture f;
    MonitorHealthConfig config;
    MonitorRuntimeStatus runtime;
    MonitorWorkManager manager;
    EmailNotificationSender sender;
    MonitorHealthService service;
    @BeforeEach void setup() {
        f=new MonitorTestFixture(); f.config.setEnabled(true);
        config=new MonitorHealthConfig(); runtime=new MonitorRuntimeStatus(f.clock);
        manager=mock(MonitorWorkManager.class); when(manager.activeWorks()).thenReturn(List.of());
        sender=mock(EmailNotificationSender.class); when(sender.isAvailable()).thenReturn(true);
        service=new MonitorHealthService(f.jdbc,new DataSourceTransactionManager(f.jdbc.getDataSource()),f.config,config,runtime,manager,sender,f.clock);
    }
    @AfterEach void close() { f.close(); }
    MonitorHealthReport inspect() { return service.inspect(24,null); }
    void scans() { runtime.scheduleStarted(); runtime.scheduleSucceeded(); runtime.mailStarted(); runtime.mailSucceeded(); }
    boolean has(MonitorHealthReport report,String code) { return report.violations().stream().anyMatch(v->v.code().equals(code)); }
    @Test void healthyEmptyAndCommittedCheckAreReadOnlyAndMatchDoesNotRequireAnEvent() throws Exception {
        assertEquals("HEALTHY",inspect().status());
        String id=f.active(2).rule().ruleId(); f.evaluate(id,Result.MATCH);
        // 连续命中要求2次；第一次 MATCH 没有事件是正常状态。
        f.clock.advance(100); scans();
        var before=f.mapper.valueToTree(f.jdbc.queryForList("SELECT * FROM monitor_rule"));
        var report=inspect();
        assertEquals(7,report.checks()); assertEquals(7,report.ran());
        assertEquals(List.of(),report.errors()); assertEquals(List.of(),report.violations());
        assertEquals(1L,report.counts().get("executionsInWindow"));
        assertEquals(0L,report.counts().get("eventsInWindow"));
        assertEquals(before,f.mapper.valueToTree(f.jdbc.queryForList("SELECT * FROM monitor_rule")));
        String encoded=f.mapper.writeValueAsString(report);
        assertFalse(encoded.contains("pilot@example.com")); assertFalse(encoded.contains("rule_spec_json"));
    }
    @Test void scheduleDetectsMissingPersistedSlotEvenIfCursorWasAdvanced() {
        String id=f.active(1).rule().ruleId(); f.executions.materialize(id);
        f.jdbc.update("DELETE FROM monitor_execution"); f.clock.advance(100); scans();
        var report=inspect();
        assertEquals(7,report.ran()); assertTrue(has(report,"SCHEDULE_GAP"));
        assertFalse(has(report,"SCHEDULE_STALLED")); assertEquals("UNHEALTHY",report.status());
    }
    @Test void newlyApprovedRuleHasGraceAndSubsequentlyReportsCursorAndMissingSlot() {
        f.active(1); assertEquals("HEALTHY",inspect().status());
        f.clock.advance(100); scans(); var report=inspect();
        assertTrue(has(report,"SCHEDULE_STALLED")); assertTrue(has(report,"SCHEDULE_GAP"));
    }
    @Test void overduePendingAndRunningAreCheckedOutsideHistoricalWindow() {
        String id=f.active(1).rule().ruleId(); f.executions.materialize(id);
        f.clock.advance(25*3600); scans();
        var report=service.inspect(1,id);
        assertEquals(0L,report.counts().get("executionsInWindow")); assertTrue(has(report,"EXECUTION_STALLED"));
        f.jdbc.update("UPDATE monitor_execution SET status='RUNNING',lease_until=NULL,attempt_deadline=NULL");
        assertTrue(has(service.inspect(1,id),"EXECUTION_STALLED"));
    }
    @Test void ownershipUsesLiveLeaseAndAcceptsExpiredLeaseAwaitingTakeover() {
        String id=f.active(1).rule().ruleId(); f.claim(id);
        assertFalse(has(inspect(),"EXECUTION_OWNERSHIP_INVALID"));
        f.jdbc.update("UPDATE monitor_rule SET lease_token=lease_token+1 WHERE rule_id=?",id);
        assertTrue(has(inspect(),"EXECUTION_OWNERSHIP_INVALID")); assertTrue(has(inspect(),"RULE_LEASE_ORPHAN"));
        f.clock.advance(400); scans(); assertFalse(has(inspect(),"EXECUTION_OWNERSHIP_INVALID"));
    }
    @Test void completedExecutionMustHaveConsistentEvaluation() {
        String id=f.active(2).rule().ruleId(); f.evaluate(id,Result.NO_MATCH);
        f.jdbc.update("DELETE FROM monitor_evaluation");
        assertTrue(has(inspect(),"DONE_WITHOUT_VALID_EVALUATION"));
    }
    @Test void evaluationCannotCommitWithoutExecutionCompletion() {
        String id=f.active(2).rule().ruleId(); f.evaluate(id,Result.NO_MATCH);
        f.jdbc.update("UPDATE monitor_execution SET status='PENDING'");
        assertTrue(has(inspect(),"EVALUATION_WITH_UNFINISHED_EXECUTION"));
    }
    @Test void eventAndOutboxIntegrityAreCheckedWithoutAssumingEveryMatchTriggers() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        assertEquals("HEALTHY",inspect().status());
        f.jdbc.update("DELETE FROM notification_delivery");
        assertTrue(has(inspect(),"EVENT_OUTBOX_MISSING"));
        f.jdbc.update("UPDATE monitor_evaluation SET result='NO_MATCH'");
        assertTrue(has(inspect(),"EVENT_EVALUATION_INVALID"));
    }
    @Test void orphanDeliveryAndInvalidSentReceiptAreDetected() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        f.jdbc.update("UPDATE notification_delivery SET status='SENT'");
        assertTrue(has(inspect(),"DELIVERY_SENT_WITHOUT_RECEIPT"));
        f.jdbc.update("DELETE FROM monitor_event");
        assertTrue(has(inspect(),"DELIVERY_EVENT_MISSING"));
    }
    @Test void failedUnknownAndOverdueMailAreReportedAndSimulatedSuccessIsCounted() {
        String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH);
        f.clock.advance(100); scans(); var pending=inspect(); assertTrue(has(pending,"DELIVERY_PENDING"));
        assertEquals("DEGRADED",pending.status()); assertEquals(1,pending.warningsTotal()); assertEquals(0,pending.errorViolationsTotal());
        for(String status:List.of("FAILED","UNKNOWN","SENDING")) {
            f.jdbc.update("UPDATE notification_delivery SET status=?",status);
            var failed=inspect(); assertTrue(has(failed,"DELIVERY_"+status)); assertEquals("UNHEALTHY",failed.status());
            assertEquals(1,failed.errorViolationsTotal()); assertEquals(0,failed.warningsTotal());
        }
        f.jdbc.update("UPDATE notification_delivery SET status='SENT',provider_message_id='dry-run:test',sent_at=?",MonitorRepository.utc(f.clock.instant()));
        when(sender.isDryRun()).thenReturn(true);
        var report=inspect(); assertEquals("HEALTHY",report.status());
        assertEquals(1L,report.counts().get("simulatedDeliveriesInWindow")); assertEquals("DRY_RUN",report.runtime().mailMode());
    }
    @Test void recoveredSchedulingWithExpiredHistoricalSlotsIsDegradedEvenWhenWarningDetailsAreTruncated() {
        String id=f.active(1).rule().ruleId(); f.clock.advance(1800);
        assertEquals(7,f.executions.materialize(id)); f.evaluate(id,Result.NO_MATCH); scans();
        config.setMaxViolations(1);
        var report=inspect();
        assertEquals("DEGRADED",report.status()); assertEquals(7,report.ran()); assertTrue(report.errors().isEmpty());
        assertEquals(6,report.violationsTotal()); assertEquals(6,report.warningsTotal()); assertEquals(0,report.errorViolationsTotal());
        assertTrue(report.truncated()); assertEquals(1,report.violations().size());
        assertEquals("EXECUTION_FAILED",report.violations().get(0).code()); assertEquals("WARN",report.violations().get(0).severity());
    }
    @Test void historicalFailureLeavingTheWindowRestoresHealthyWhenSubsequentSlotsSucceed() {
        String id=f.active(1).rule().ruleId(); var work=f.claim(id);
        f.executions.fail(work,new MonitorWorkFailure("INVALID_DEFINITION","test",false));
        assertEquals("DEGRADED",service.inspect(1,id).status());
        for(int i=0;i<12;i++) { f.clock.advance(300); f.evaluate(id,Result.NO_MATCH); }
        f.clock.advance(1); scans();
        var report=service.inspect(1,id); assertEquals("HEALTHY",report.status());
        assertEquals(0,report.warningsTotal()); assertEquals(0,report.errorViolationsTotal()); assertTrue(report.violations().isEmpty());
    }
    @Test void anErrorHiddenByTheOverallResponseLimitStillMakesTheReportUnhealthy() {
        String id=f.active(1).rule().ruleId(); var work=f.claim(id);
        f.executions.fail(work,new MonitorWorkFailure("INVALID_DEFINITION","test",false));
        when(sender.isAvailable()).thenReturn(false); config.setMaxViolations(1);
        var report=inspect();
        assertEquals("UNHEALTHY",report.status()); assertEquals(2,report.violationsTotal());
        assertEquals(1,report.warningsTotal()); assertEquals(1,report.errorViolationsTotal()); assertTrue(report.truncated());
        assertEquals("WARN",report.violations().get(0).severity()); assertFalse(has(report,"MAIL_NOT_CONFIGURED"));
    }
    @Test void anErrorHiddenByASingleSqlQueryLimitIsCountedAcrossTheCompleteResult() {
        String failed=f.active(1).rule().ruleId(); var work=f.claim(failed);
        f.executions.fail(work,new MonitorWorkFailure("INVALID_DEFINITION","test",false));
        String pending=f.active(1).rule().ruleId(); f.executions.materialize(pending);
        f.clock.advance(100); scans(); config.setMaxViolations(1);
        JdbcTemplate warningFirst=new JdbcTemplate(f.jdbc.getDataSource()) {
            @Override public List<java.util.Map<String,Object>> queryForList(String sql,Object...args) {
                // 让截断列表确定地只包含WARN；计数查询仍读取真实、完整的SQL结果。
                if(sql.startsWith("SELECT e.rule_id,e.execution_id,e.status,e.attempt_count") && sql.endsWith(" LIMIT ?"))
                    sql=sql.replace(" LIMIT ?"," ORDER BY CASE WHEN e.status='FAILED' THEN 0 ELSE 1 END LIMIT ?");
                return super.queryForList(sql,args);
            }
        };
        var tx=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc.getDataSource()));
        var report=new MonitorHealthService(warningFirst,tx,f.config,config,runtime,manager,sender,f.clock).inspect(24,null);
        assertEquals("UNHEALTHY",report.status()); assertEquals(2,report.violationsTotal());
        assertEquals(1,report.warningsTotal()); assertEquals(1,report.errorViolationsTotal()); assertTrue(report.truncated());
        assertEquals("WARN",report.violations().get(0).severity()); assertFalse(has(report,"EXECUTION_STALLED"));
    }
    @Test void scanStartDoesNotMaskFailedOrStalledScanAndRecoveryClearsFailure() {
        f.clock.advance(61); runtime.scheduleStarted(); runtime.mailStarted();
        assertTrue(has(inspect(),"SCHEDULE_SCAN_STALLED")); assertTrue(has(inspect(),"MAIL_SCAN_STALLED"));
        runtime.scheduleFailed(new IllegalStateException("secret SQL")); runtime.mailSucceeded();
        var failed=inspect(); assertTrue(has(failed,"SCHEDULE_SCAN_FAILED"));
        assertFalse(failed.toString().contains("secret SQL"));
        runtime.scheduleStarted(); runtime.scheduleSucceeded(); assertEquals("HEALTHY",inspect().status());
        assertNotNull(inspect().runtime().schedule().lastFailedAt());
    }
    @Test void sqlFailureIsIncompleteAndDoesNotIncrementRanOrLeakSql() {
        JdbcTemplate broken=new JdbcTemplate(f.jdbc.getDataSource()) {
            @Override public <T> T queryForObject(String sql,Class<T> type,Object...args) {
                if(sql.contains("SELECT e.rule_id,e.execution_id,e.status,e.attempt_count"))
                    throw new DataAccessResourceFailureException("password and SQL must not escape");
                return super.queryForObject(sql,type,args);
            }
        };
        var tx=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc.getDataSource()));
        var brokenService=new MonitorHealthService(broken,tx,f.config,config,runtime,manager,sender,f.clock);
        var report=brokenService.inspect(24,null);
        assertEquals(7,report.checks()); assertEquals(6,report.ran()); assertEquals("INCOMPLETE",report.status());
        assertEquals(List.of(new MonitorHealthReport.CheckError("EXECUTION_STALLED","CHECK_QUERY_FAILED")),report.errors());
        assertTrue(report.violations().isEmpty()); assertFalse(report.toString().contains("password"));
    }
    @Test void crossTenantSummaryAndRuleFilterDoNotDependOnVisitorCookie() {
        String first=f.active(1).rule().ruleId(), second=f.active(1).rule().ruleId();
        f.jdbc.update("UPDATE monitor_rule SET owner_id='v:alice' WHERE rule_id=?",first);
        f.jdbc.update("UPDATE monitor_rule SET owner_id='v:bob' WHERE rule_id=?",second);
        f.clock.advance(100); scans();
        assertEquals(2L,inspect().counts().get("activeRules"));
        var one=service.inspect(24,first); assertEquals(1L,one.counts().get("activeRules"));
        assertTrue(one.violations().stream().allMatch(v->first.equals(v.ruleId())));
    }
    @Test void responseLimitReportsExactTotalAndExcessScopeFailsClosed() {
        f.active(1); f.active(1); f.clock.advance(100); scans(); config.setMaxViolations(1);
        var report=inspect(); assertTrue(report.truncated()); assertEquals(1,report.violations().size());
        assertEquals(4,report.violationsTotal());
        config.setMaxRules(1); var large=inspect(); assertEquals("INCOMPLETE",large.status()); assertEquals(6,large.ran());
        assertEquals("CHECK_SCOPE_TOO_LARGE",large.errors().get(0).code());
    }
    @Test void disabledRuntimeIsExplicitAndDoesNotTreatPausedSchedulingAsStalled() {
        f.active(1); f.clock.advance(100); f.config.setEnabled(false); when(sender.isAvailable()).thenReturn(false);
        var report=inspect(); assertEquals("DISABLED",report.status()); assertEquals(7,report.ran()); assertTrue(report.violations().isEmpty());
    }
}
