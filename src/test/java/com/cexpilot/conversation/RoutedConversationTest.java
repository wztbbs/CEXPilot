package com.cexpilot.conversation;

import com.cexpilot.config.ConversationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立 H2 验证真实收尾 SQL 与事务；不连接线上库或调用 LLM。 */
class RoutedConversationTest {
    final ObjectMapper json=new ObjectMapper();
    JdbcTemplate jdbc; ConversationRepository repository; ConversationService service; TransactionTemplate tx;
    final ConversationService.TurnHandle turn=new ConversationService.TurnHandle(1,"turn",7);
    final Instant now=Instant.parse("2026-10-27T06:00:00Z");
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:agent_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source); tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        repository=spy(new ConversationRepository(jdbc)); service=new ConversationService(repository,new ConversationConfig());
        jdbc.update("INSERT INTO conversation (conversation_id) VALUES ('c')");
        jdbc.update("""
                INSERT INTO conversation_state (conversation_id,running_turn_id,fencing_token,active_frame_id,active_frame_json,active_agent_type)
                VALUES ('c','turn',7,'query-frame','{}','query')
                """);
        jdbc.update("INSERT INTO conversation_query (conversation_id,query_no,question,turn_id,turn_status) VALUES ('c',1,'监控BTC','turn','RUNNING')");
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    private void finish(String agent,String status,com.fasterxml.jackson.databind.JsonNode payload) {
        tx.execute(ignored->service.finishRoutedTurn("c",turn,"监控BTC",status,"规划答复","trace",null,json.createArrayNode(),
                TaskScope.current(null),now,ZoneOffset.UTC,null,agent,payload));
    }
    @Test void monitorPlanningDoesNotOverwriteQueryFrameAndSavesCandidateForLaterTurns() {
        var payload=json.createObjectNode().put("status","PENDING_CONFIRMATION"); payload.putObject("confirmation").put("ruleId","draft").put("version",1); payload.putObject("candidate").putObject("monitor").put("action","create");
        finish("monitor","SUCCEEDED",payload);
        assertEquals("query-frame",jdbc.queryForObject("SELECT active_frame_id FROM conversation_state",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_frame",Integer.class));
        assertEquals("monitor",service.routingState("c").path("active_agent").asText());
        assertEquals("create",service.routingState("c").at("/monitor_context/candidate/monitor/action").asText());
        assertEquals("monitor",jdbc.queryForObject("SELECT agent_type FROM conversation_query",String.class));
        assertEquals("draft",repository.allQueries("c").get(0).planning().at("/confirmation/ruleId").asText());
        assertEquals("draft",service.routingState("c").at("/monitor_context/confirmation/ruleId").asText());
        assertNull(jdbc.queryForObject("SELECT logical_plan_json FROM conversation_query",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM monitor_rule",Integer.class));
        assertNull(jdbc.queryForObject("SELECT running_turn_id FROM conversation_state",String.class));
    }
    @Test void monitorClarificationRetainsPriorCandidateAndQuerySwitchRetainsMonitorContext() {
        jdbc.update("UPDATE conversation_state SET monitor_planning_json=?",json.createObjectNode()
                .set("candidate",json.createObjectNode().put("marker","previous")).toString());
        finish("monitor","NEEDS_CLARIFICATION",json.createObjectNode().put("status","NEEDS_CLARIFICATION"));
        assertEquals("previous",service.routingState("c").at("/monitor_context/candidate/marker").asText());
        jdbc.update("UPDATE conversation_state SET running_turn_id='turn'");
        finish("query","SUCCEEDED",null);
        assertEquals("query",service.routingState("c").path("active_agent").asText());
        assertEquals("previous",service.routingState("c").at("/monitor_context/candidate/marker").asText());
    }
    @Test void staleTurnCannotOverwriteRoutingFocusOrPlanningContext() {
        jdbc.update("UPDATE conversation_state SET running_turn_id='newer',fencing_token=8,active_agent_type='query'");
        finish("monitor","SUCCEEDED",json.createObjectNode().putObject("candidate").put("bad",true));
        assertEquals("query",service.routingState("c").path("active_agent").asText());
        assertFalse(service.routingState("c").path("monitor_context").has("candidate"));
        assertEquals("newer",jdbc.queryForObject("SELECT running_turn_id FROM conversation_state",String.class));
    }
    @Test void contextWriteFailureRollsBackCompletionAndLeaseRelease() {
        doThrow(new IllegalStateException("write failed")).when(repository).saveRoutingState(anyString(),anyLong(),anyString(),any());
        assertThrows(IllegalStateException.class,()->finish("monitor","SUCCEEDED",json.createObjectNode()));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT turn_status FROM conversation_query",String.class));
        assertEquals("turn",jdbc.queryForObject("SELECT running_turn_id FROM conversation_state",String.class));
        assertNull(jdbc.queryForObject("SELECT agent_type FROM conversation_query",String.class));
    }
    @Test void presetReplySavesHistoryWithoutReplacingPendingTaskOrMonitorFocus() {
        jdbc.update("UPDATE conversation_state SET active_agent_type='monitor',pending_task_json=?,monitor_planning_json=?",
                "{\"marker\":\"pending\"}","{\"candidate\":{\"marker\":\"monitor\"}}");
        var before=jdbc.queryForMap("SELECT active_frame_id,active_frame_json,pending_task_json,active_agent_type,monitor_planning_json FROM conversation_state");
        var payload=json.createObjectNode().put("source","preset").put("preset_id","capabilities");
        var result=tx.execute(ignored->service.finishQueryTurn("c",turn,"你能实现哪些功能？","SUCCEEDED",
                "固定能力介绍","trace",null,json.createArrayNode(),null,now,ZoneOffset.UTC,null,payload));
        assertFalse(result.updated());
        var after=jdbc.queryForMap("SELECT active_frame_id,active_frame_json,pending_task_json,active_agent_type,monitor_planning_json FROM conversation_state");
        // H2 JSON 返回 byte[]，按值比较而非数组对象地址。
        before.forEach((key,value)->assertEquals(value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value,
                after.get(key) instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):after.get(key)));
        assertNull(jdbc.queryForObject("SELECT running_turn_id FROM conversation_state",String.class));
        var history=repository.allQueries("c").get(0);
        assertEquals("固定能力介绍",history.answer());
        assertEquals("preset",history.planning().path("source").asText());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_frame",Integer.class));
    }
}
