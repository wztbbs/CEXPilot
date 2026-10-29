package com.cexpilot.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ClarificationFieldTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void bareTextFieldsAndReferencesRoundTripWithoutControlMetadata() throws Exception {
        var fields=ClarificationField.parse(json.readTree("""
                [{"key":"base","label":"币种","reason":"missing","message":"请填写币种","options":["BTC","ETH"]},
                 {"key":"count","label":"期数","reason":"invalid","message":"最多100期，您填写200","value":200}]
                """));
        assertEquals(2,fields.size());
        assertEquals("200",fields.get(1).value().asText());
        assertEquals(fields,ClarificationField.parse(json.valueToTree(fields)));
        assertFalse(json.valueToTree(fields).get(0).has("type"));
        assertTrue(ClarificationField.question(fields).contains("您填写200"));
    }

    @Test void formerlyInvalidControlCombinationsAreIgnoredInsteadOfBlockingForm() throws Exception {
        var fields=ClarificationField.parse(json.readTree("""
                [{"key":"exchange","label":"交易所","type":"number","min":"","max":-1,
                  "minSelections":3,"maxSelections":1,"options":["币安","OKX"],"reason":"missing","message":"请填写两家"},
                 {"key":"mode","label":"比较方式","type":"select"}]
                """));
        assertEquals(2,fields.size());
        assertEquals(java.util.List.of("币安","OKX"),fields.get(0).options());
        var output=json.valueToTree(fields);
        for (String obsolete:java.util.List.of("type","min","max","minSelections","maxSelections")) assertFalse(output.get(0).has(obsolete));
    }

    @Test void optionalReferencesAndDisplayHintsCannotInvalidateEssentialQuestions() throws Exception {
        var fields=ClarificationField.parse(json.readTree("""
                [{"key":"exchange","label":"交易所","options":["OKX",3,null,"","OKX"],"placeholder":"","hint":7},
                 {"key":"base","label":"币种","options":{"unexpected":true}}]
                """));
        assertEquals(java.util.List.of("OKX"),fields.get(0).options());
        assertNull(fields.get(0).placeholder());
        assertTrue(fields.get(1).options().isEmpty());
    }

    @Test void oldArrayValuesArePreservedIncludingAliasesAndRepeatedText() throws Exception {
        var fields=ClarificationField.parse(json.readTree("""
                [{"key":"exchange","label":"交易所","type":"multiselect","options":["币安","OKX"],"value":["币安","未知","币安"]}]
                """));
        assertEquals(3,fields.get(0).value().size());
        assertEquals(fields,ClarificationField.parse(json.valueToTree(fields)));
    }

    @ParameterizedTest @ValueSource(strings={
            "{\"key\":\"x\",\"label\":\"\"}",
            "{\"key\":\"bad.key\",\"label\":\"字段\"}",
            "{\"key\":\"x\",\"label\":\"字段\",\"reason\":\"unknown\"}",
            "{\"key\":\"x\",\"label\":\"字段\",\"reason\":\"invalid\"}",
            "{\"key\":\"x\",\"label\":\"字段\",\"reason\":\"confirm\"}"
    })
    void identityAndSpecificCorrectionReasonsAreStillRequired(String item) throws Exception {
        var node=json.readTree("["+item+"]");
        assertThrows(IllegalArgumentException.class,()->ClarificationField.parse(node));
    }

    @Test void duplicateKeysAndOversizedListsRemainStructuralErrors() throws Exception {
        var item=json.readTree("{\"key\":\"x\",\"label\":\"币种\"}");
        var list=json.createArrayNode().add(item).add(item);
        assertThrows(IllegalArgumentException.class,()->ClarificationField.parse(list));
        for (int i=0;i<7;i++) list.add(item);
        assertThrows(IllegalArgumentException.class,()->ClarificationField.parse(list));
    }
}
