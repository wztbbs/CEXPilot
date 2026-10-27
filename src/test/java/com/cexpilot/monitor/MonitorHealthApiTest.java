package com.cexpilot.monitor;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MonitorHealthApiTest {
    static final String TOKEN="01234567890123456789012345678901";
    MonitorTestFixture f;
    MonitorHealthService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        f=new MonitorTestFixture(); f.config.setAccessToken(TOKEN); f.config.setEnabled(true);
        var sender=mock(EmailNotificationSender.class); when(sender.isAvailable()).thenReturn(true);
        var manager=mock(MonitorWorkManager.class); when(manager.activeWorks()).thenReturn(List.of());
        service=new MonitorHealthService(f.jdbc,new DataSourceTransactionManager(f.jdbc.getDataSource()),f.config,
                new MonitorHealthConfig(),new MonitorRuntimeStatus(f.clock),manager,sender,f.clock);
        mvc=MockMvcBuilders.standaloneSetup(new MonitorHealthController(service)).addFilters(new MonitorAccessFilter(f.config)).build();
    }
    @AfterEach void close() { f.close(); }
    @Test void bearerIsMandatoryAndHealthRemainsReadableWhenSchedulingIsDisabled() throws Exception {
        mvc.perform(get("/api/monitor-health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/monitor-health;ignored")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/monitor-health/child").header("Authorization","Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/monitor-health").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.ran").value(7))
                .andExpect(jsonPath("$.status").value("HEALTHY"));
        f.config.setEnabled(false);
        mvc.perform(get("/api/monitor-health").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.status").value("DISABLED"));
        f.config.setAccessToken("");
        mvc.perform(get("/api/monitor-health").header("Authorization","Bearer "+TOKEN)).andExpect(status().isServiceUnavailable());
    }
    @Test void invalidScopeAndUnknownRuleAreRejected() throws Exception {
        for(String value:List.of("0","25","-1","abc"))
            mvc.perform(get("/api/monitor-health").param("sinceHours",value).header("Authorization","Bearer "+TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/monitor-health").param("ruleId","bad' input").header("Authorization","Bearer "+TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/monitor-health").param("ruleId","missing").header("Authorization","Bearer "+TOKEN)).andExpect(status().isNotFound());
    }
    @Test void checkFailureReturns503WithUsableReportInsteadOfHealthy200() throws Exception {
        f.jdbc.execute("DROP TABLE monitor_execution");
        mvc.perform(get("/api/monitor-health").header("Authorization","Bearer "+TOKEN)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("INCOMPLETE")).andExpect(jsonPath("$.checks").value(7))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }
    @Test void completedInspectionWithViolationsReturns200AndUnhealthyConclusion() throws Exception {
        f.active(1); f.clock.advance(100);
        mvc.perform(get("/api/monitor-health").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNHEALTHY")).andExpect(jsonPath("$.ran").value(7))
                .andExpect(jsonPath("$.violations").isNotEmpty());
    }
    @Test void warningsOnlyReturn200WithDegradedStatusAndSeparateSeverityTotals() throws Exception {
        String id=f.active(1).rule().ruleId(); var work=f.claim(id);
        f.executions.fail(work,new MonitorWorkFailure("INVALID_DEFINITION","test",false));
        mvc.perform(get("/api/monitor-health").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED")).andExpect(jsonPath("$.ran").value(7))
                .andExpect(jsonPath("$.violationsTotal").value(1)).andExpect(jsonPath("$.warningsTotal").value(1))
                .andExpect(jsonPath("$.errorViolationsTotal").value(0)).andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.violations[0].severity").value("WARN"));
    }
}
