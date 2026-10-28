package com.cexpilot.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.LocalDateTime;
import static com.cexpilot.monitor.MonitorModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** 旧结构升级到单Execution租约，真实SQL且只连接H2。 */
class MonitorExecutionLeaseMigrationTest {
    @Test void migrationDropsObsoleteFieldsWithoutExtendingRunningLeaseOrLosingCheckpoints() {
        try(var f=new MonitorTestFixture()) {
            f.jdbc.execute("ALTER TABLE monitor_rule ADD COLUMN lease_until DATETIME(6)");
            f.jdbc.execute("ALTER TABLE monitor_rule ADD COLUMN lease_token BIGINT DEFAULT 0 NOT NULL");
            f.jdbc.execute("ALTER TABLE monitor_execution ADD COLUMN attempt_deadline DATETIME(6)");
            String id=f.active(1).rule().ruleId(); var work=f.claim(id);
            var checkpoint=f.mapper.createObjectNode().put("saved",42); assertTrue(f.executions.checkpoint(work,checkpoint));
            f.jdbc.update("UPDATE monitor_execution SET lease_until=?,attempt_deadline=? WHERE execution_id=?",
                    MonitorRepository.utc(f.clock.instant().plusSeconds(120)),MonitorRepository.utc(f.clock.instant().plusSeconds(40)),work.executionId());
            new ResourceDatabasePopulator(new ClassPathResource("db/migrations/018_monitor_execution_lease.sql")).execute(f.jdbc.getDataSource());
            var restored=f.executions.find(work.executionId());
            assertEquals(f.clock.instant().plusSeconds(40),restored.leaseUntil());
            assertEquals(work.execution().leaseToken(),restored.leaseToken()); assertEquals(checkpoint,restored.checkpoint());
            assertEquals(MonitorExecution.State.RUNNING,restored.status());
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='monitor_rule' AND column_name IN ('lease_until','lease_token')",Integer.class));
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='monitor_execution' AND column_name='attempt_deadline'",Integer.class));
            f.clock.advance(41); var retry=f.executions.claim(work.executionId()); assertNotNull(retry);
            assertTrue(retry.execution().leaseToken()>work.execution().leaseToken()); assertEquals(checkpoint,retry.execution().checkpoint());
            assertFalse(f.executions.complete(work,f.check(Result.MATCH))); assertTrue(f.executions.complete(retry,f.check(Result.MATCH)));
        }
    }
}
