package com.cexpilot.monitor;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MonitorEmailAddressTest {
    @ParameterizedTest @ValueSource(strings={"friend@example.com","first.last+cex@hrscrm.net","USER@Sub.Example.com"})
    void supportsOrdinaryMailboxAndTags(String value) { assertTrue(MonitorEmailAddress.valid(value)); }
    @ParameterizedTest @NullSource @ValueSource(strings={"","Name <a@example.com>","a@example.com,b@example.com","a@example.com\r\nBcc: x@y.com","a..b@example.com",".a@example.com","a@-example.com","a@localhost"," a@example.com"})
    void rejectsHeadersListsAndMalformedAddress(String value) { assertFalse(MonitorEmailAddress.valid(value)); }
}
