package com.cexpilot.monitor;

import com.cexpilot.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 默认关闭时整应用仍能启动，且新线程池不影响既有 SSE 执行器。使用完整新库 schema。 */
@SpringBootTest(classes=Application.class,properties={
        "spring.datasource.url=jdbc:h2:mem:monitor_context;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "monitor.enabled=false","logging.file.name=target/monitor-context.log"
})
@AutoConfigureMockMvc
class MonitorApplicationContextTest {
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired MonitorConfig config;
    @Autowired MockMvc mvc;
    @Test void fullSchemaAndExistingExecutorRemainAvailable() {
        assertFalse(config.isEnabled());
        assertInstanceOf(TaskExecutor.class,context.getBean("applicationTaskExecutor"));
        assertNotNull(context.getBean(MonitorScheduler.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM monitor_rule",Integer.class));
    }
    @Test void removedLegacyRoutesAndAssetsAreNoLongerExposed() throws Exception {
        for(String path:java.util.List.of("/api/monitors","/api/monitors/removed","/monitors.html","/monitors.js"))
            mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitors").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
}
