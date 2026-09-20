package com.cexpilot.intent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从 classpath:intents/*.yml 真实加载意图定义。
 */
class IntentRegistryTest {

    private final IntentRegistry registry = new IntentRegistry(new DefaultResourceLoader());

    @Test
    void loadsMarketLookupIntent() {
        IntentDefinition intent = registry.find("MARKET_LOOKUP");

        assertNotNull(intent);
        assertTrue(intent.description().contains("行情"));
        assertEquals("MARKET_LOOKUP", intent.name());
        assertFalse(intent.evidenceRules().isEmpty());
    }

    @Test
    void unknownIntentReturnsNull() {
        assertNull(registry.find("NOT_EXIST"));
        assertNull(registry.find(null));
    }
}
