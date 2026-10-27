package com.cexpilot.monitor;

import com.cexpilot.evidence.EvidenceSession;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.cexpilot.monitor.MonitorModels.*;

/** SQL/事务、版本和浏览器归属真实验证，外部服务全部替身。 */
class MonitorConversationFlowTest {
    MonitorTestFixture f;
    MonitorConversationFlow flow;
    MonitorDefinitionCompiler compiler;
    MonitorPlanRunner runner;
    EmailNotificationSender mail;
    ObjectNode definition;
    @BeforeEach void setup() {
        f=new MonitorTestFixture(); f.config.setEnabled(true);
        compiler=mock(MonitorDefinitionCompiler.class); runner=mock(MonitorPlanRunner.class);
        definition=f.mapper.createObjectNode().put("schemaVersion",2).put("timezone","Asia/Shanghai").put("requiredHits",2).put("catalogVersion","test");
        definition.putObject("candidate").put("example",1);
        definition.putArray("rules").addObject().put("id","condition_1").put("description","BTC价格变化率小于-2%").put("valueRef","{{m1.binance.value}}").put("threshold",-2).put("operator","LT").put("unit","percent");
        when(compiler.compile(any(),any())).thenAnswer(inv->definition.deepCopy());
        mail=mock(EmailNotificationSender.class); when(mail.isAvailable()).thenReturn(true);
        flow=new MonitorConversationFlow(f.jdbc,f.repository,compiler,runner,f.config,f.json,f.clock,mail);
        f.jdbc.update("INSERT INTO conversation (conversation_id,visitor_id) VALUES ('c','alice')");
        f.jdbc.update("INSERT INTO conversation_state (conversation_id,running_turn_id,lease_until) VALUES ('c','t1',?)",LocalDateTime.now().plusHours(1));
    }
    @AfterEach void close() { f.close(); }
    ObjectNode draft(String turn) { return flow.saveDraft("alice",new EvidenceSession("c",turn),"监控BTC",f.mapper.createObjectNode(),"Asia/Shanghai"); }
    ConfirmRequest request(ObjectNode card) { return new ConfirmRequest(card.path("version").asInt(),card.path("specHash").asText()); }
    void release() { f.jdbc.update("UPDATE conversation_state SET running_turn_id=NULL WHERE conversation_id='c'"); }
    void turn(String id) { f.jdbc.update("UPDATE conversation_state SET running_turn_id=?,lease_until=? WHERE conversation_id='c'",id,LocalDateTime.now().plusHours(1)); }
    String id(ObjectNode card) { return card.path("ruleId").asText(); }
    void conflict(Runnable action) { assertEquals(409,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }
    @Test void draftCannotRunUntilExplicitConfirmationAndRecipientIsPinned() {
        var card=draft("t1"); String id=id(card);
        assertEquals("PENDING_CONFIRMATION",card.path("status").asText());
        assertFalse(card.path("canConfirm").asBoolean()); assertTrue(f.repository.due(f.clock.instant()).isEmpty());
        assertEquals(1,f.count("monitor_rule")); assertEquals(0,f.count("monitor_evaluation"));
        flow.bindEmail("alice","a@example.com");
        conflict(()->flow.confirm("alice",id,request(card),"a@example.com")); // 本轮尚未收尾
        release();
        conflict(()->flow.confirm("alice",id,request(card),"changed@example.com"));
        var active=flow.confirm("alice",id,request(card),"a@example.com");
        assertEquals("ACTIVE",active.path("status").asText());
        assertEquals("a@example.com",f.repository.find("v:alice",id,false).recipient());
        var next=f.repository.find("v:alice",id,false).nextCheckAt();
        f.clock.advance(100);
        flow.confirm("alice",id,request(card),"a@example.com");
        assertEquals(next,f.repository.find("v:alice",id,false).nextCheckAt()); // 重试不重新激活
        flow.bindEmail("alice","other@example.com");
        assertEquals("a@example.com",flow.card("alice",id,null).path("recipient").asText());
        assertEquals(0,f.count("monitor_evaluation"));
    }
    @Test void revisedDraftSupersedesOldVersionAndCannotBeApprovedThroughOldCard() {
        var old=draft("t1");
        assertEquals(id(old),id(draft("t1"))); assertEquals(1,f.count("monitor_rule_version"));
        ((ObjectNode)definition.at("/rules/0")).put("threshold",-3).put("description","BTC跌3%");
        conflict(()->draft("t1")); // 同一个回合不能偷偷换内容
        turn("t2"); var latest=draft("t2"); release(); flow.bindEmail("alice","a@example.com");
        assertEquals(id(old),id(latest)); assertEquals(2,latest.path("version").asInt());
        assertEquals("SUPERSEDED",flow.card("alice",id(old),1).path("status").asText());
        conflict(()->flow.confirm("alice",id(old),request(old),"a@example.com"));
        conflict(()->flow.reject("alice",id(old),request(old)));
        assertEquals("ACTIVE",flow.confirm("alice",id(latest),request(latest),"a@example.com").path("status").asText());
    }
    @Test void sameEmailDoesNotGiveAnotherBrowserOwnershipOrRevealTasks() {
        var card=draft("t1"); release();
        flow.bindEmail("alice","a@example.com"); flow.bindEmail("bob","a@example.com");
        assertTrue(flow.list("bob").isEmpty());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->flow.card("bob",id(card),null)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->flow.confirm("bob",id(card),request(card),"a@example.com")).getStatusCode().value());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->flow.profile(null)).getStatusCode().value());
        f.jdbc.update("UPDATE conversation SET visitor_id='bob' WHERE conversation_id='c'"); turn("t2");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->draft("t2")).getStatusCode().value());
    }
    @Test void unavailableDraftCanBeSavedButCannotActivateAndCancellationIsFinal() {
        var card=draft("t1"); release();
        conflict(()->flow.confirm("alice",id(card),request(card),"a@example.com"));
        flow.bindEmail("alice","a@example.com"); f.config.setEnabled(false);
        assertFalse(flow.card("alice",id(card),1).path("canConfirm").asBoolean());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->flow.confirm("alice",id(card),request(card),"a@example.com")).getStatusCode().value());
        f.config.setEnabled(true);
        doThrow(new IllegalArgumentException("粒度不支持")).when(runner).validateRunnable(any());
        assertFalse(flow.card("alice",id(card),1).path("canConfirm").asBoolean());
        flow.reject("alice",id(card),request(card));
        conflict(()->flow.confirm("alice",id(card),request(card),"a@example.com"));
        assertTrue(f.repository.due(f.clock.instant()).isEmpty());
    }
    @Test void executionLeaseLossLeavesNoDraftAndMailboxSyntaxIsValidatedWithoutOtp() {
        f.jdbc.update("UPDATE conversation_state SET lease_until=? WHERE conversation_id='c'",LocalDateTime.now().minusMinutes(1));
        conflict(()->draft("t1")); assertEquals(0,f.count("monitor_rule"));
        for(String invalid:new String[]{"broken","a@example.com,b@example.com","Name <a@example.com>","a@example.com\r\nBcc: x@y.com"})
            assertThrows(IllegalArgumentException.class,()->flow.bindEmail("alice",invalid));
        assertTrue(flow.bindEmail("alice"," a@example.com ").path("emailBound").asBoolean());
        assertEquals(1,f.count("monitor_subscriber"));
    }
    @Test void missingResendConfigurationBlocksConfirmationButKeepsDraft() {
        when(mail.isAvailable()).thenReturn(false);
        var card=draft("t1"); release(); flow.bindEmail("alice","a@example.com");
        assertFalse(card.path("executionAvailable").asBoolean());
        assertTrue(card.path("unavailableReason").asText().contains("Resend"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->flow.confirm("alice",id(card),request(card),"a@example.com")).getStatusCode().value());
        assertTrue(f.repository.due(f.clock.instant()).isEmpty());
    }
    @Test void immutableRuleThresholdSurvivesDatabaseRoundTripWithoutFloatingPointRounding() {
        var exact=new java.math.BigDecimal("2.0000000000000001");
        ((ObjectNode)definition.at("/rules/0")).put("threshold",exact);
        var card=draft("t1");
        assertEquals(exact,card.at("/rules/0/threshold").decimalValue());
        assertEquals(exact,f.repository.version(id(card),1).definition().at("/rules/0/threshold").decimalValue());
        assertEquals(card.path("specHash"),draft("t1").path("specHash"));
    }
    @Test void cookieApiRequiresExplicitMutationHeaderAndVersionedConfirmation() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new MonitorFlowController(flow)).build();
        var cookie=new jakarta.servlet.http.Cookie("cexpilot_uid","alice");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/monitor-flow/profile"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var card=draft("t1"); release();
        String path="/api/monitor-flow/rules/"+id(card)+"/confirm";
        String body=f.mapper.createObjectNode().put("version",card.path("version").asInt()).put("specHash",card.path("specHash").asText()).put("recipient","a@example.com").toString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(cookie).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertTrue(f.repository.due(f.clock.instant()).isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/monitor-flow/profile").cookie(cookie)
                .header("X-Monitor-Action","1").contentType("application/json").content("{\"email\":\"a@example.com\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(cookie).header("X-Monitor-Action","1").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ACTIVE"));
        f.executions.materialize(id(card));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/monitor-flow/rules/"+id(card)+"/executions").cookie(cookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].status").value("PENDING"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].attemptCount").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/monitor-flow/rules/"+id(card)+"/executions").cookie(new jakarta.servlet.http.Cookie("cexpilot_uid","bob")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/monitor-flow/rules/"+id(card)+"/events").cookie(new jakarta.servlet.http.Cookie("cexpilot_uid","bob")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    @Test void confirmedPlansRunThroughThePlanWorker() {
        var card=draft("t1"); release(); flow.bindEmail("alice","a@example.com");
        flow.confirm("alice",id(card),request(card),"a@example.com");
        when(runner.run(any(MonitorWork.class),any())).thenReturn(f.check(Result.MATCH));
        var worker=new MonitorWorker(runner);
        var manager=new MonitorWorkManager(f.executions,worker,null,f.clock);
        f.run(manager,id(card)); f.run(manager,id(card));
        verify(runner,times(1)).run(any(MonitorWork.class),any());
        assertEquals(1,f.count("monitor_evaluation"));
    }
    @Test void confirmedPlanRunsThroughSharedTransactionOutboxAndStopInvalidatesWorker() {
        var card=draft("t1"); release(); flow.bindEmail("alice","a@example.com");
        flow.confirm("alice",id(card),request(card),"a@example.com");
        f.evaluate(id(card),Result.MATCH); assertEquals(0,f.count("monitor_event"));
        f.clock.advance(300); f.evaluate(id(card),Result.MATCH);
        var event=f.repository.events(id(card)).get(0).path("evidence");
        assertEquals(2,event.path("schemaVersion").asInt()); assertEquals(2,event.path("checks").size());
        var delivery=f.repository.claimDelivery(f.repository.dueDeliveries().get(0));
        assertEquals("a@example.com",delivery.recipient());
        assertTrue(EmailNotificationSender.body(delivery).contains("BTC价格变化率小于-2%"));
        assertTrue(EmailNotificationSender.body(delivery).contains("?monitor="+id(card)));
        assertEquals("BTC价格变化率小于-2%",delivery.snapshot().at("/rules/0/description").asText());
        f.clock.advance(300); var claim=f.claim(id(card));
        flow.stop("alice",id(card),request(card));
        assertFalse(f.executions.complete(claim,f.check(Result.MATCH)));
        conflict(()->flow.confirm("alice",id(card),request(card),"a@example.com"));
    }
}
