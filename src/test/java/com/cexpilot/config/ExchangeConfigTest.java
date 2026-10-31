package com.cexpilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeConfigTest {
    @Test void spotProxyBindsIndependentlyFromPerpetualAndOkx() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ExchangeConfig.class)
                .withPropertyValues("exchange.binance.base-url=http://perpetual.test",
                        "exchange.binance.spot-base-url=http://spot.test", "exchange.okx.base-url=http://okx.test")
                .run(context -> {
                    var config = context.getBean(ExchangeConfig.class);
                    assertEquals("http://spot.test", config.getBinance().getSpotBaseUrl());
                    assertEquals("http://perpetual.test", config.getBinance().getBaseUrl());
                    assertEquals("http://okx.test", config.getOkx().getBaseUrl());
                });
    }
}
