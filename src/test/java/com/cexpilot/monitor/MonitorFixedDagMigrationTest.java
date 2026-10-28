package com.cexpilot.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class MonitorFixedDagMigrationTest {
    @Test void removesExecutionHashWhileKeepingVersionConfirmationHashAndCheckpointAuditData() {
        try(var f=new MonitorTestFixture()) {
            f.jdbc.execute("ALTER TABLE monitor_execution ADD COLUMN spec_hash CHAR(64)");
            String rule=f.active(1).rule().ruleId(); String hash=f.detail(rule).version().specHash();
            var work=f.claim(rule); var saved=f.mapper.createObjectNode().put("schemaVersion",1).put("specHash",hash);
            assertTrue(f.executions.checkpoint(work,saved));
            new ResourceDatabasePopulator(new ClassPathResource("db/migrations/019_monitor_fixed_dag.sql")).execute(f.jdbc.getDataSource());
            assertEquals(hash,f.detail(rule).version().specHash()); assertEquals(saved,f.executions.find(work.executionId()).checkpoint());
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='monitor_execution' AND column_name='spec_hash'",Integer.class));
        }
    }
}
