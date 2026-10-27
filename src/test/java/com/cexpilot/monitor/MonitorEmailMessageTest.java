package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorEmailMessageTest {
    @Test void multiConditionEmailIncludesEveryHitActualValueAndFreezesContents() {
        var json=new ObjectMapper(); var event=json.createObjectNode().put("schemaVersion",2).put("ruleId","rule-1");
        event.putArray("rules").addObject().put("description","价格变化大于2%");
        event.withArray("rules").addObject().put("description","持仓量变化大于3%");
        event.putObject("definition").put("requiredHits",2);
        var checks=event.putArray("checks");
        for(String slot:java.util.List.of("2026-10-27T06:00:00Z","2026-10-27T06:05:00Z")) {
            var values=checks.addObject().put("slot",slot).putObject("evidence").putArray("values");
            values.addObject().put("id","condition_1").put("value","2.75").put("unit","percent");
            values.addObject().put("id","condition_2").put("value","4.25").put("unit","percent");
        }
        var request=MonitorEmailMessage.freeze(event,"event-1","support@hrscrm.net","a@example.com");
        var text=request.path("text").asText();
        assertTrue(text.contains("价格变化大于2%")); assertTrue(text.contains("持仓量变化大于3%"));
        assertTrue(text.contains("condition_1：2.75%")); assertTrue(text.contains("condition_2：4.25%"));
        assertTrue(text.contains("06:00:00Z")); assertTrue(text.contains("06:05:00Z"));
        assertTrue(text.contains("event-1")); assertTrue(text.contains("?monitor=rule-1"));
        event.remove("checks"); assertEquals(text,request.path("text").asText());
        assertFalse(event.has("resend_request")); // 纯构造，不修改调用方证据
    }
}
