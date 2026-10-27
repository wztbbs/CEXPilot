package com.cexpilot.monitor;

import com.cexpilot.config.ExchangeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

/** 只绑定部署配置并启动配置Bean，不访问任何交易所接口。 */
class MonitorConfigTest {
    @Test void chatMonitoringRequiresValidSenderAndExecutionSettingsWithoutGlobalRecipient() {
        var config=new MonitorConfig(); assertDoesNotThrow(config::validate);
        config.setEnabled(true); assertThrows(IllegalArgumentException.class,config::validate);
        config.setMailFrom("from@example.com"); assertDoesNotThrow(config::validate);
        config.setAccessToken("short"); assertThrows(IllegalArgumentException.class,config::validate);
        config.setAccessToken("01234567890123456789012345678901"); assertDoesNotThrow(config::validate);
        config.setWorkTimeoutSeconds(config.getLeaseSeconds()); assertThrows(IllegalArgumentException.class,config::validate);
    }
    @Test void enabledMonitorAcceptsDeploymentConfiguredExchangeEndpoints() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(MonitorConfig.class,ExchangeConfig.class)
                .withPropertyValues("monitor.enabled=true","monitor.mail-from=support@hrscrm.net",
                        "exchange.binance.base-url=https://fapi.binance.com","exchange.okx.base-url=https://www.okx.com")
                .run(context->{
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(MonitorConfig.class).isEnabled());
                    var exchanges=context.getBean(ExchangeConfig.class);
                    assertEquals("https://fapi.binance.com",exchanges.getBinance().getBaseUrl());
                    assertEquals("https://www.okx.com",exchanges.getOkx().getBaseUrl());
                });
    }
}
