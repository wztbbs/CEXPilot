package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;

/** 邮件请求与业务证据一并冻结；之后更换模板或发件人不会改变相同幂等键的载荷。 */
final class MonitorEmailMessage {
    private MonitorEmailMessage() {}
    static ObjectNode freeze(JsonNode snapshot,String eventId,String from,String recipient) {
        var request=JsonNodeFactory.instance.objectNode().put("from",from)
                .put("subject","CEXPilot 行情监控触发")
                .put("text",body(snapshot,eventId));
        request.putArray("to").add(recipient);
        request.putObject("headers").put("X-CEXPilot-Event",eventId);
        return request;
    }
    static String body(JsonNode snapshot,String eventId) {
        var out=new StringBuilder("CEXPilot 监控条件已触发（全部条件同时满足）\n\n");
        var descriptions=new HashMap<String,String>();
        for(var rule:snapshot.path("rules")) {
            String description=rule.path("description").asText();
            if(!description.isBlank()) {
                descriptions.put(rule.path("id").asText(),description);
                out.append("• ").append(description).append('\n');
            }
        }
        var checks=snapshot.path("checks");
        out.append("连续命中 ").append(snapshot.at("/definition/requiredHits").asInt())
                .append(" 次即触发（本轮已连续命中 ").append(checks.size()).append(" 次）\n");
        ZoneId zone=timezone(snapshot.at("/definition/timezone").asText());
        int index=0;
        for(var check:snapshot.path("checks")) {
            out.append("\n第 ").append(++index).append(" 次命中，检查截止时间：")
                    .append(displayTime(check.path("slot").asText(),zone)).append('\n');
            for(var value:check.at("/evidence/values")) out.append(descriptions.getOrDefault(value.path("id").asText(),"监控条件"))
                        .append("；实际值：")
                        .append(value.path("value").asText()).append(MonitorDefinitionCompiler.unitLabel(value.path("unit").asText())).append('\n');
        }
        return out.append("\n时间按规则时区 ").append(zone.getId()).append(" 显示，并附 UTC 时间。告警 ID：").append(eventId)
                    .append("\n查看监控：https://cex.hrscrm.net/?monitor=").append(snapshot.path("ruleId").asText()).toString();
    }
    private static ZoneId timezone(String value) {
        try { return ZoneId.of(value); }
        catch(DateTimeException invalid) { return ZoneOffset.UTC; }
    }
    private static String displayTime(String value,ZoneId zone) {
        try {
            var instant=Instant.parse(value);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(zone).format(instant)+"（UTC："+instant+"）";
        } catch(DateTimeException invalid) { return "原始时间："+value; }
    }
}
