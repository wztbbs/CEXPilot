package com.cexpilot.monitor;

import com.resend.Resend;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.monitor.MonitorModels.*;

/** 真实 SDK → 本地 HTTP 桩，绝不连接 Resend 或发送邮件。 */
class ResendEmailSenderTest {
    MonitorTestFixture f; HttpServer server; ResendConfig config; EmailNotificationSender sender; Resend sdk;
    final Queue<Response> responses=new ConcurrentLinkedQueue<>();
    final List<Request> requests=new CopyOnWriteArrayList<>();
    record Response(int status,String body) {}
    record Request(String key,String auth,String body) {}
    @BeforeEach void setup() throws Exception {
        f=new MonitorTestFixture(); config=new ResendConfig(); config.setApiKey("re_local_test_only");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/emails",exchange->{
            requests.add(new Request(exchange.getRequestHeaders().getFirst("Idempotency-Key"),exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            Response response=responses.poll(); if(response==null) response=new Response(200,"{\"id\":\"email-test-1\"}");
            byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(response.status(),bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        sdk=Resend.builder().apiKey(config.getApiKey()).baseUrl("http://127.0.0.1:"+server.getAddress().getPort())
                .connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(1)).callTimeout(Duration.ofSeconds(2)).build();
        sender=new EmailNotificationSender(config,f.config,f.clock,sdk);
    }
    @AfterEach void close() { server.stop(0); f.close(); }
    Delivery delivery() { return delivery("pilot@example.com"); }
    Delivery delivery(String recipient) {
        String id=f.active(1).rule().ruleId();
        f.jdbc.update("UPDATE monitor_rule SET recipient=? WHERE rule_id=?",recipient,id);
        f.evaluate(id,Result.MATCH);
        return f.repository.claimDelivery(f.repository.dueDeliveries().get(0));
    }
    void error(int code,String name) { responses.add(new Response(code,"{\"name\":\""+name+"\",\"message\":\"sensitive-provider-echo\"}")); }
    @Test void defaultSmokeRecipientNeverCallsHttpEvenWithRealModeAndAnAvailableSdk() {
        assertFalse(config.isDryRun()); assertTrue(sender.isAvailable());
        var smoke=delivery("MONITOR-SMOKE@hrscrm.net"); var result=sender.send(smoke);
        assertEquals("SENT",result.status()); assertEquals("dry-run:"+smoke.deliveryId(),result.messageId());
        assertTrue(requests.isEmpty());
        f.repository.finishDelivery(smoke,result.status(),result.messageId(),result.error());
        assertTrue(f.repository.events(f.jdbc.queryForObject("SELECT rule_id FROM monitor_event WHERE event_id=?",String.class,smoke.eventId()))
                .get(0).path("simulated").asBoolean());
        assertEquals("SENT",sender.send(delivery()).status());
        assertEquals(1,requests.size()); assertTrue(requests.get(0).body().contains("pilot@example.com"));
    }
    @Test void configuredSmokeRecipientSkipsHttpAndStillValidatesFrozenRequestAndExpiry() {
        config.setDryRunRecipient("custom-smoke@example.com");
        var smoke=delivery("custom-smoke@example.com"); assertEquals("SENT",sender.send(smoke).status());
        ((ObjectNode)smoke.snapshot()).remove("resend_request");
        assertEquals("UNKNOWN",sender.send(smoke).status());
        f.clock.advance(23*3600); assertEquals("UNKNOWN",sender.send(smoke).status()); assertTrue(requests.isEmpty());
    }
    @Test void sendsFrozenPayloadAndPersistsReturnedProviderId() throws Exception {
        var delivery=delivery(); f.config.setMailFrom("changed@example.com");
        var result=sender.send(delivery);
        assertEquals("SENT",result.status()); assertEquals("email-test-1",result.messageId());
        assertEquals("cexpilot/"+delivery.deliveryId(),requests.get(0).key());
        assertEquals("Bearer re_local_test_only",requests.get(0).auth());
        var payload=f.mapper.readTree(requests.get(0).body());
        assertEquals("monitor@example.com",payload.path("from").asText());
        assertEquals("pilot@example.com",payload.at("/to/0").asText());
        assertTrue(payload.path("text").asText().contains("-3%"));
        assertTrue(payload.path("text").asText().contains(delivery.eventId()));
        assertEquals(delivery.eventId(),payload.at("/headers/X-CEXPilot-Event").asText());
        f.repository.finishDelivery(delivery,result.status(),result.messageId(),result.error());
        assertEquals("email-test-1",f.jdbc.queryForObject("SELECT provider_message_id FROM notification_delivery",String.class));
    }
    @Test void retryAcrossSenderRestartUsesIdenticalKeyAndFullRequest() {
        var delivery=delivery(); error(503,"service_unavailable");
        var first=sender.send(delivery); assertEquals("RETRY",first.status()); assertTrue(first.uncertain());
        f.repository.finishDelivery(delivery,"PENDING",null,MonitorRepository.UNCERTAIN_PREFIX+first.error());
        f.clock.advance(300); f.config.setMailFrom("new-sender@example.com");
        var retry=f.repository.claimDelivery(delivery.deliveryId());
        var restarted=new EmailNotificationSender(config,f.config,f.clock,sdk);
        var result=restarted.send(retry); assertEquals("SENT",result.status());
        assertEquals(requests.get(0).key(),requests.get(1).key()); assertEquals(requests.get(0).body(),requests.get(1).body());
        assertTrue(retry.uncertain());
    }
    @Test void classifiesRateLimitPermanentErrorsAndIdempotencyConflictWithoutLeakingResponse() {
        var delivery=delivery();
        error(429,"rate_limit_exceeded"); assertEquals("RETRY",sender.send(delivery).status());
        error(401,"validation_error"); var denied=sender.send(delivery);
        assertEquals("FAILED",denied.status()); assertFalse(denied.error().contains("sensitive-provider-echo"));
        error(422,"validation_error"); assertEquals("FAILED",sender.send(delivery).status());
        error(409,"concurrent_idempotent_requests"); assertEquals("RETRY",sender.send(delivery).status());
        error(409,"invalid_idempotent_request"); assertEquals("UNKNOWN",sender.send(delivery).status());
        responses.add(new Response(200,"{}")); assertTrue(sender.send(delivery).uncertain());
    }
    @Test void missingConfigurationExpiredWindowOrBrokenSnapshotNeverMakesRequest() {
        var delivery=delivery();
        config.setApiKey(""); assertFalse(sender.isAvailable()); assertEquals("RETRY",sender.send(delivery).status());
        config.setApiKey("re_local_test_only");
        ((ObjectNode)delivery.snapshot()).remove("resend_request");
        assertEquals("UNKNOWN",sender.send(delivery).status());
        f.clock.advance(23*3600);
        assertEquals("UNKNOWN",sender.send(delivery).status()); assertTrue(requests.isEmpty());
    }
    @Test void nullIdAndConnectionFailureRemainRetryableButUncertain() {
        var delivery=delivery(); responses.add(new Response(200,"{\"id\":null}"));
        assertTrue(sender.send(delivery).uncertain());
        server.stop(0);
        var failure=sender.send(delivery); assertEquals("RETRY",failure.status()); assertTrue(failure.uncertain());
    }
    @Test void expiredSendingCannotRetryPastIdempotencyWindowAndOldTransportIsNotResubmitted() {
        var old=delivery();
        f.clock.advance(23*3600);
        assertNull(f.repository.claimDelivery(old.deliveryId()));
        assertEquals("UNKNOWN",f.jdbc.queryForObject("SELECT status FROM notification_delivery",String.class));
        assertTrue(requests.isEmpty());
    }
}
