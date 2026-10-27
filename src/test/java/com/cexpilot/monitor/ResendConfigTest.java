package com.cexpilot.monitor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResendConfigTest {
    @Test void springBindsDryRunWithoutRequiringAnApiKeyOrConstructingAnHttpClient() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ResendConfig.class,MonitorConfig.class,EmailNotificationSender.class)
                .withBean(java.time.Clock.class,java.time.Clock::systemUTC)
                .withPropertyValues("resend.dry-run=true","resend.api-key=","resend.base-url=unused-invalid-url",
                        "monitor.mail-from=test@example.com")
                .run(context->{
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(ResendConfig.class).isDryRun());
                    assertTrue(context.getBean(EmailNotificationSender.class).isAvailable());
                });
    }
    @Test void blankBaseUrlUsesSdkDefaultWithoutRequiringAnOverride() {
        var config=new ResendConfig(); config.setApiKey("re_local_test_only");
        assertEquals("",config.getBaseUrl());
        var monitor=new MonitorConfig(); monitor.setMailFrom("support@hrscrm.net");
        // 仅构造客户端，绝不调用 send 或访问官方地址。
        assertTrue(new EmailNotificationSender(config,monitor,java.time.Clock.systemUTC()).isAvailable());
    }
    @Test void blankKeyLeavesQueryApplicationAvailableAndValidKeyEnablesSenderConfiguration() {
        var config=new ResendConfig(); assertFalse(config.isConfigured()); assertDoesNotThrow(config::validate);
        config.setApiKey(" re_fake_local_key "); assertTrue(config.isConfigured());
        assertEquals("re_fake_local_key",config.getApiKey()); assertDoesNotThrow(config::validate);
        config.setApiKey(null); assertFalse(config.isConfigured());
    }
    @Test void rejectsAccidentalMultiLineKeyWithoutEchoingSecret() {
        var config=new ResendConfig(); config.setApiKey("re_secret\nsecond_line");
        var error=assertThrows(IllegalArgumentException.class,config::validate);
        assertFalse(error.getMessage().contains("re_secret"));
    }
}
