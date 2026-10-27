package com.cexpilot.monitor;

import com.cexpilot.Application;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.monitor.MonitorModels.*;

/** 整应用真实装配 → Outbox → 真实Resend SDK → 本地HTTP桩 → 投递状态回写。无真实邮件、行情或LLM。 */
@SpringBootTest(classes=Application.class,properties={
        "spring.datasource.url=jdbc:h2:mem:resend_wiring;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "monitor.enabled=true","monitor.mail-from=support@hrscrm.net",
        "resend.api-key=re_local_spring_test_only","resend.dry-run=false","logging.file.name=target/resend-wiring.log"
})
@Import(ResendSpringWiringTest.TimeFixture.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ResendSpringWiringTest {
    static final Instant NOW=Instant.parse("2026-10-27T07:05:20Z");
    static HttpServer server;
    static final AtomicReference<String> requestBody=new AtomicReference<>();
    static final AtomicReference<String> idempotency=new AtomicReference<>();
    static final AtomicReference<String> authorization=new AtomicReference<>();
    @DynamicPropertySource static void localMail(DynamicPropertyRegistry properties) throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/emails",exchange->{
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body="{\"id\":\"email-spring-stub-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        properties.add("resend.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterAll static void stop() { if(server!=null) server.stop(0); }
    @TestConfiguration(proxyBeanMethods=false) static class TimeFixture {
        @Bean @Primary Clock scenarioClock() { return Clock.fixed(NOW,ZoneOffset.UTC); }
    }
    @Autowired MonitorConversationFlow flow;
    // 自动扫描只入空执行器；此用例显式驱动检查和本地桩投递，避免后台访问行情。
    @MockBean MonitorExecutors executor;
    @Autowired MonitorExecutionService executions;
    @Autowired MonitorRepository repository;
    @Autowired NotificationWorker notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResendConfig config;
    @Autowired ObjectMapper json;

    @Test void springConstructedSenderHonorsBaseUrlAndCompletesOutboxDelivery() throws Exception {
        assertEquals("http://127.0.0.1:"+server.getAddress().getPort(),config.getBaseUrl());
        jdbc.update("INSERT INTO conversation (conversation_id,visitor_id) VALUES ('mail-conversation','mail-visitor')");
        jdbc.update("INSERT INTO conversation_state (conversation_id,running_turn_id,lease_until) VALUES ('mail-conversation','mail-turn',?)",LocalDateTime.now().plusHours(1));
        flow.bindEmail("mail-visitor","friend@example.com");
        var draft=flow.saveDraft("mail-visitor",new com.cexpilot.evidence.EvidenceSession("mail-conversation","mail-turn"),
                "local stub verification",MonitorTestFixture.candidate(1),"Asia/Shanghai");
        jdbc.update("UPDATE conversation_state SET running_turn_id=NULL WHERE conversation_id='mail-conversation'");
        String ruleId=draft.path("ruleId").asText();
        flow.confirm("mail-visitor",ruleId,new ConfirmRequest(1,draft.path("specHash").asText()),"friend@example.com");
        assertEquals(1,executions.materialize(ruleId));
        var work=executions.claim(executions.list(ruleId).get(0).executionId());
        var evidence=json.createObjectNode(); evidence.putArray("values").addObject().put("id","condition_1").put("value","-3").put("unit","percent");
        assertTrue(executions.complete(work,new Check(Result.MATCH,evidence,null)));
        String deliveryId=repository.dueDeliveries().get(0); notifications.run(deliveryId);
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM notification_delivery WHERE delivery_id=?",String.class,deliveryId));
        assertEquals("email-spring-stub-1",jdbc.queryForObject("SELECT provider_message_id FROM notification_delivery WHERE delivery_id=?",String.class,deliveryId));
        assertEquals("cexpilot/"+deliveryId,idempotency.get());
        assertEquals("Bearer re_local_spring_test_only",authorization.get());
        JsonNode payload=json.readTree(requestBody.get());
        assertEquals("friend@example.com",payload.at("/to/0").asText()); assertTrue(payload.path("text").asText().contains("-3%"));
        assertEquals(1,repository.events(ruleId).size());
        assertTrue(repository.dueDeliveries().isEmpty());
    }
}
