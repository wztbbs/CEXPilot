package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
        for(var rule:snapshot.path("rules")) out.append("• ").append(rule.path("description").asText()).append('\n');
        out.append("连续命中：").append(snapshot.at("/definition/requiredHits").asInt()).append(" 次检查\n");
        for(var check:snapshot.path("checks")) {
            out.append("\n检查截止时间：").append(check.path("slot").asText()).append('\n');
            for(var value:check.at("/evidence/values")) out.append(value.path("id").asText()).append("：")
                        .append(value.path("value").asText()).append(MonitorDefinitionCompiler.unitLabel(value.path("unit").asText())).append('\n');
        }
        return out.append("\n时间使用 UTC。告警 ID：").append(eventId)
                    .append("\n查看监控：https://cex.hrscrm.net/?monitor=").append(snapshot.path("ruleId").asText()).toString();
    }
}
