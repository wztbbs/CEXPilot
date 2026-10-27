package com.cexpilot.monitor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resend.Resend;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static com.cexpilot.monitor.MonitorModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResendDryRunTest {
    MonitorTestFixture f;
    ResendConfig config;
    @BeforeEach void setup() { f=new MonitorTestFixture(); config=new ResendConfig(); }
    @AfterEach void close() { f.close(); }
    String prepare() { String id=f.active(1).rule().ruleId(); f.evaluate(id,Result.MATCH); return id; }
    @Test void noKeyDryRunDoesNotConstructHttpClientAndPersistsClearlyMarkedSuccess() {
        assertFalse(config.isDryRun()); config.setDryRun(true); config.setBaseUrl("invalid-unused-url");
        var sender=new EmailNotificationSender(config,f.config,f.clock); assertTrue(sender.isAvailable());
        String ruleId=prepare(), deliveryId=f.repository.dueDeliveries().get(0);
        Logger logger=(Logger)LoggerFactory.getLogger(EmailNotificationSender.class);
        var logs=new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var worker=new NotificationWorker(f.repository,f.config,sender,null);
            worker.run(deliveryId); worker.run(deliveryId);
            assertEquals(1,logs.list.size()); String line=logs.list.get(0).getFormattedMessage();
            assertTrue(line.contains(deliveryId)); assertTrue(line.contains("DRY_RUN")); assertFalse(line.contains("@example.com"));
        } finally { logger.detachAppender(logs); logs.stop(); }
        var event=f.repository.events(ruleId).get(0);
        assertEquals("SENT",event.path("deliveryStatus").asText()); assertTrue(event.path("simulated").asBoolean());
        assertEquals("dry-run:"+deliveryId,event.path("providerMessageId").asText());
        assertTrue(f.repository.dueDeliveries().isEmpty());
    }
    @Test void dryRunNeverInvokesSdkButPreservesSnapshotAndExpiryValidation() {
        config.setDryRun(true); var sdk=mock(Resend.class); var sender=new EmailNotificationSender(config,f.config,f.clock,sdk);
        prepare(); var delivery=f.repository.claimDelivery(f.repository.dueDeliveries().get(0));
        assertEquals("SENT",sender.send(delivery).status()); verifyNoInteractions(sdk);
        ((ObjectNode)delivery.snapshot()).remove("resend_request");
        assertEquals("UNKNOWN",sender.send(delivery).status()); verifyNoInteractions(sdk);
        f.clock.advance(23*3600); assertEquals("UNKNOWN",sender.send(delivery).status()); verifyNoInteractions(sdk);
    }
    @Test void defaultRealModeWithNoKeyDoesNotPretendToSend() {
        var sender=new EmailNotificationSender(config,f.config,f.clock);
        assertFalse(sender.isAvailable()); prepare();
        assertEquals("RETRY",sender.send(f.repository.claimDelivery(f.repository.dueDeliveries().get(0))).status());
    }
}
