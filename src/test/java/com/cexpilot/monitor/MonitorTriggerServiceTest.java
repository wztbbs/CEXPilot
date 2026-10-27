package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import java.time.Instant;
import static com.cexpilot.monitor.MonitorModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** 文档 5.3 节的六条转移：连续命中、跨周期打断、UNKNOWN 不解除 WAITING_RESET。 */
class MonitorTriggerServiceTest {
    static final Instant SLOT=Instant.parse("2026-10-26T06:05:00Z");
    final MonitorTriggerService trigger=new MonitorTriggerService();
    Rule rule(int hits,String state,Instant lastSlot,boolean evidence) {
        var array=JsonNodeFactory.instance.arrayNode();
        if(evidence) array.addObject().put("slot",lastSlot.toString());
        return new Rule("r","o","q","ACTIVE",1,SLOT,lastSlot,hits,state,array,null,0,SLOT,SLOT);
    }
    Check check(Result result) { return new Check(result,JsonNodeFactory.instance.objectNode().put("value","-3"),null); }

    @Test void firstMatchCountsWithoutTriggering() {
        var next=trigger.apply(rule(0,"ARMED",null,false),2,SLOT,check(Result.MATCH));
        assertEquals(1,next.hits()); assertEquals("ARMED",next.state()); assertFalse(next.trigger());
        assertEquals(1,next.hitEvidence().size());
        assertEquals(SLOT.toString(),next.hitEvidence().get(0).path("slot").asText());
    }

    @Test void consecutiveMatchReachesRequiredHitsAndWaitsForReset() {
        var next=trigger.apply(rule(1,"ARMED",SLOT.minusSeconds(300),true),2,SLOT,check(Result.MATCH));
        assertEquals(2,next.hits()); assertEquals("WAITING_RESET",next.state()); assertTrue(next.trigger());
        assertEquals(2,next.hitEvidence().size(),"两次命中的证据都要进邮件");
    }

    @Test void waitingResetSwallowsFurtherMatchesUntilANoMatchRearms() {
        var stuck=trigger.apply(rule(0,"WAITING_RESET",SLOT.minusSeconds(300),false),2,SLOT,check(Result.MATCH));
        assertEquals(0,stuck.hits()); assertEquals("WAITING_RESET",stuck.state()); assertFalse(stuck.trigger());
        var rearmed=trigger.apply(rule(0,"WAITING_RESET",SLOT.minusSeconds(300),false),2,SLOT,check(Result.NO_MATCH));
        assertEquals("ARMED",rearmed.state()); assertEquals(0,rearmed.hitEvidence().size()); assertFalse(rearmed.trigger());
    }

    @Test void noMatchClearsEvidenceSoTheNextAlertCannotCiteIt() {
        var next=trigger.apply(rule(1,"ARMED",SLOT.minusSeconds(300),true),2,SLOT,check(Result.NO_MATCH));
        assertEquals(0,next.hits()); assertEquals("ARMED",next.state());
        assertEquals(0,next.hitEvidence().size());
    }

    @Test void unknownBreaksTheCountButNeverReleasesWaitingReset() {
        for(String state:new String[]{"ARMED","WAITING_RESET"}) {
            var next=trigger.apply(rule(1,state,SLOT.minusSeconds(300),true),2,SLOT,check(Result.UNKNOWN));
            assertEquals(0,next.hits()); assertEquals(state,next.state()); assertFalse(next.trigger());
            assertEquals(0,next.hitEvidence().size());
        }
    }

    @Test void skippedCycleRestartsTheCountInsteadOfCountingAsConsecutive() {
        var next=trigger.apply(rule(1,"ARMED",SLOT.minusSeconds(600),true),2,SLOT,check(Result.MATCH));
        assertEquals(1,next.hits(),"中间漏掉一个周期就不能算连续命中");
        assertEquals("ARMED",next.state()); assertFalse(next.trigger());
        assertEquals(1,next.hitEvidence().size(),"跨周期要丢掉旧证据");
    }

    @Test void requiredHitsIsReadFromThePinnedVersionRatherThanTheCachedRule() {
        var next=trigger.apply(rule(0,"ARMED",null,false),1,SLOT,check(Result.MATCH));
        assertTrue(next.trigger());
        assertEquals("WAITING_RESET",next.state());
    }
}
