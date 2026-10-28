package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorEmailMessageTest {
    @Test void multiConditionEmailIncludesEveryHitActualValueAndFreezesContents() {
        var json=new ObjectMapper(); var event=json.createObjectNode().put("schemaVersion",2).put("ruleId","rule-1");
        event.putArray("rules").addObject().put("id","condition_2").put("description","持仓量变化大于3%");
        event.withArray("rules").addObject().put("id","condition_1").put("description","价格变化大于2%");
        event.putObject("definition").put("requiredHits",2).put("timezone","Asia/Shanghai");
        var checks=event.putArray("checks");
        for(String slot:java.util.List.of("2026-10-27T06:00:00Z","2026-10-27T06:05:00Z")) {
            var values=checks.addObject().put("slot",slot).putObject("evidence").putArray("values");
            values.addObject().put("id","condition_1").put("value","2.75").put("unit","percent");
            values.addObject().put("id","condition_2").put("value","4.25").put("unit","percent");
        }
        var request=MonitorEmailMessage.freeze(event,"event-1","support@hrscrm.net","a@example.com");
        var text=request.path("text").asText();
        assertTrue(text.contains("价格变化大于2%")); assertTrue(text.contains("持仓量变化大于3%"));
        assertTrue(text.contains("价格变化大于2%；实际值：2.75%")); assertTrue(text.contains("持仓量变化大于3%；实际值：4.25%"));
        assertFalse(text.contains("condition_"));
        assertTrue(text.contains("连续命中 2 次即触发（本轮已连续命中 2 次）"));
        assertTrue(text.contains("第 1 次命中，检查截止时间：2026-10-27 14:00:00 +08:00（UTC：2026-10-27T06:00:00Z）"));
        assertTrue(text.contains("第 2 次命中，检查截止时间：2026-10-27 14:05:00 +08:00（UTC：2026-10-27T06:05:00Z）"));
        assertTrue(text.contains("规则时区 Asia/Shanghai"));
        assertTrue(text.contains("event-1")); assertTrue(text.contains("?monitor=rule-1"));
        event.remove("checks"); assertEquals(text,request.path("text").asText());
        assertFalse(event.has("resend_request")); // 纯构造，不修改调用方证据
    }
    @Test void singleHitUsesTheReadableRuleAndExplainsTheActualHitCount() {
        var event=new ObjectMapper().createObjectNode();
        event.putObject("definition").put("requiredHits",1).put("timezone","Asia/Shanghai");
        event.putArray("rules").addObject().put("id","condition_1").put("description","Binance BTC/USDT 永续，当前 成交价 大于 10000 USDT");
        event.putArray("checks").addObject().put("slot","2026-10-28T07:10:00Z").putObject("evidence").putArray("values")
                .addObject().put("id","condition_1").put("value","80918.80").put("unit","quote");
        String text=MonitorEmailMessage.body(event,"event-1");
        assertTrue(text.contains("当前 成交价 大于 10000 USDT；实际值：80918.80 USDT"));
        assertTrue(text.contains("连续命中 1 次即触发（本轮已连续命中 1 次）"));
        assertTrue(text.contains("2026-10-28 15:10:00 +08:00（UTC：2026-10-28T07:10:00Z）"));
        assertFalse(text.contains("condition_1"));
    }
    @Test void timezoneOffsetsDistinguishRepeatedLocalTimesDuringDaylightSavingTransition() {
        var event=new ObjectMapper().createObjectNode();
        event.putObject("definition").put("requiredHits",2).put("timezone","America/New_York");
        var checks=event.putArray("checks");
        checks.addObject().put("slot","2026-11-01T05:30:00Z"); checks.addObject().put("slot","2026-11-01T06:30:00Z");
        String text=MonitorEmailMessage.body(event,"event-1");
        assertTrue(text.contains("2026-11-01 01:30:00 -04:00（UTC：2026-11-01T05:30:00Z）"));
        assertTrue(text.contains("2026-11-01 01:30:00 -05:00（UTC：2026-11-01T06:30:00Z）"));
    }
    @Test void historicalMissingDescriptionOrTimezoneDoesNotExposeInternalIdsOrBreakRendering() {
        var event=new ObjectMapper().createObjectNode(); event.putObject("definition").put("requiredHits",1);
        var check=event.putArray("checks").addObject().put("slot","2026-10-28T07:10:00Z");
        check.putObject("evidence").putArray("values").addObject().put("id","condition_1").put("value","1").put("unit","percent");
        String text=MonitorEmailMessage.body(event,"event-1");
        assertTrue(text.contains("监控条件；实际值：1%")); assertTrue(text.contains("2026-10-28 07:10:00 Z"));
        assertFalse(text.contains("condition_1"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)event.path("definition")).put("timezone","invalid/zone");
        assertEquals(text,MonitorEmailMessage.body(event,"event-1"));
    }
}
