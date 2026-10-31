package com.cexpilot.trace;

import com.cexpilot.api.TraceController;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TraceSourceTest {
    JdbcTemplate jdbc; TraceRepository repository;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:trace_source_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);repository=new TraceRepository(jdbc);
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    @Test void writesBooleanPerRequestAndTraceReadsExposeIt() throws Exception {
        repository.startTrace("user","same-conversation","q1","model","v","visitor","build");
        repository.startTrace("script","same-conversation","q2","model","v","visitor","build",false);
        assertEquals(true,repository.findTrace("user").get("is_real_user"));
        assertEquals(false,repository.findTrace("script").get("is_real_user"));
        var mvc=MockMvcBuilders.standaloneSetup(new TraceController(repository)).build();
        mvc.perform(get("/api/trace/user")).andExpect(jsonPath("$.trace.is_real_user").value(true));
        mvc.perform(get("/api/trace_detail/script")).andExpect(jsonPath("$.trace.is_real_user").value(false));
    }
    @Test void migrationLeavesHistoricalTrafficUnknownAndSetsFutureDefault() throws Exception {
        jdbc.execute("ALTER TABLE ask_trace DROP COLUMN is_real_user");
        jdbc.update("INSERT INTO ask_trace(trace_id,question) VALUES ('historical','old question')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migrations/020_trace_real_user.sql")).execute(jdbc.getDataSource());
        assertNull(repository.findTrace("historical").get("is_real_user"));
        jdbc.update("INSERT INTO ask_trace(trace_id,question) VALUES ('new-default','new question')");
        assertEquals(true,repository.findTrace("new-default").get("is_real_user"));
        repository.startTrace("script","c","q","m","v","visitor","b",false);
        assertEquals(false,repository.findTrace("script").get("is_real_user"));
        var mvc=MockMvcBuilders.standaloneSetup(new TraceController(repository)).build();
        String body=mvc.perform(get("/api/trace_detail/historical")).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"is_real_user\":null"));
    }
}
