package com.cexpilot.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClarificationAnswerTest {
    private List<QueryRecord> history() throws Exception {
        var payload = new ObjectMapper().readTree("""
                {"status":"NEEDS_CLARIFICATION","clarify":[
                 {"key":"count","label":"期数","type":"number","min":1,"max":100}]}
                """);
        return List.of(new QueryRecord(1,"比较Funding","请补期数","trace",null,"parent","NEEDS_CLARIFICATION","query",payload));
    }

    @Test void invalidBusinessValueCanReachCorrectiveClarificationInsteadOfBeingTreatedAsValid() throws Exception {
        var answers = ClarificationAnswer.validate("parent",history(),Map.of("count","200"));
        assertEquals("200",answers.get(0).value().asText());
        assertTrue(ClarificationAnswer.render(answers).contains("不代表已验证正确"));
    }

    private List<QueryRecord> multiHistory() throws Exception {
        var payload=new ObjectMapper().readTree("""
                {"status":"NEEDS_CLARIFICATION","clarify":[{"key":"exchange","label":"交易所","type":"multiselect",
                 "options":["币安","OKX","第三家"],"minSelections":2,"maxSelections":2}]}
                """);
        return List.of(new QueryRecord(1,"两所比较","请选择两家","t",null,"parent","NEEDS_CLARIFICATION","query",payload));
    }

    @Test void multiSelectionIsAnArrayAndEnforcesTwoOfThreeWithoutChangingSingleValues() throws Exception {
        var history=multiHistory();
        var answer=ClarificationAnswer.validate("parent",history,Map.of("exchange",List.of("币安","OKX"))).get(0);
        assertTrue(answer.value().isArray());
        assertEquals(2,answer.value().size());
        for (Object invalid : List.of("币安",List.of(),List.of("币安"),List.of("币安","OKX","第三家"),List.of("币安","未知"),List.of("币安","币安"))) {
            assertThrows(IllegalArgumentException.class, () -> ClarificationAnswer.validate("parent",history,Map.of("exchange",invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> ClarificationAnswer.validate("parent",history(),Map.of("count",List.of("10"))));
    }

    @Test void unknownFieldOrWrongParentCannotInventFormFields() throws Exception {
        var history = history();
        assertThrows(IllegalArgumentException.class, () -> ClarificationAnswer.validate("other",history,Map.of("count","10")));
        assertThrows(IllegalArgumentException.class, () -> ClarificationAnswer.validate("parent",history,Map.of("base","BTC")));
        assertThrows(IllegalArgumentException.class, () -> ClarificationAnswer.validate(null,history,Map.of("count","10")));
    }
}
