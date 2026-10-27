package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;
import java.time.Instant;
import static com.cexpilot.monitor.MonitorModels.*;

/** 纯状态转换；UNKNOWN 打断连续计数，但不解除 WAITING_RESET。 */
@Component
public class MonitorTriggerService {
    public Transition apply(Rule rule, int requiredHits, Instant slot, Check check) {
        var checks = JsonNodeFactory.instance.arrayNode();
        if (check.result()==Result.UNKNOWN) return new Transition(0,rule.triggerState(),checks,false);
        if (check.result()==Result.NO_MATCH) return new Transition(0,"ARMED",checks,false);
        if ("WAITING_RESET".equals(rule.triggerState())) return new Transition(0,"WAITING_RESET",checks,false);
        boolean consecutive = rule.lastEvaluatedSlot()!=null && rule.lastEvaluatedSlot().plusSeconds(300).equals(slot);
        int hits = consecutive?rule.consecutiveHits():0;
        if (hits>0) checks.addAll((com.fasterxml.jackson.databind.node.ArrayNode)rule.hitEvidence());
        var item = checks.addObject();
        item.put("slot",slot.toString()); item.set("evidence",check.evidence());
        hits++;
        boolean fire = hits>=requiredHits;
        return new Transition(hits,fire?"WAITING_RESET":"ARMED",checks,fire);
    }
}
