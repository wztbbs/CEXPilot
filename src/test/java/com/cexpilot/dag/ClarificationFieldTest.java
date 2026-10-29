package com.cexpilot.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ClarificationFieldTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void roundTripMultipleInputTypes() throws Exception {
        var node = json.readTree("""
                [{"key":"base","label":"币种","type":"text","placeholder":"例如 BTC"},
                 {"key":"count","label":"期数","type":"number","min":1,"max":100},
                 {"key":"start","label":"开始日期","type":"date"},
                 {"key":"exchange","label":"交易所","type":"select","options":["币安","OKX"]}]
                """);
        var fields = ClarificationField.parse(node);
        assertEquals(4, fields.size());
        assertEquals(fields, ClarificationField.parse(json.valueToTree(fields)));
        assertTrue(ClarificationField.question(fields).contains("期数"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"key\":\"x\",\"label\":\"\",\"type\":\"text\"}",
            "{\"key\":\"x\",\"label\":\"字段\",\"type\":\"password\"}",
            "{\"key\":\"x\",\"label\":\"字段\",\"type\":\"select\",\"options\":[]}",
            "{\"key\":\"x\",\"label\":\"字段\",\"type\":\"select\",\"options\":[\"币安\",\"币安\"]}",
            "{\"key\":\"x\",\"label\":\"字段\",\"type\":\"number\",\"min\":10,\"max\":1}",
            "{\"key\":\"x\",\"label\":\"字段\",\"type\":\"text\",\"min\":1}",
            "{\"key\":\"x\",\"label\":\"字段\",\"type\":\"number\",\"min\":\"1\"}"
    })
    void invalidFieldsCannotBecomeAForm(String item) throws Exception {
        var node = json.readTree("[" + item + "]");
        assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(node));
    }

    @Test void missingOptionsAndDuplicateKeysHaveSpecificErrors() throws Exception {
        var noOptions = json.readTree("[{\"key\":\"comparison\",\"label\":\"比较方式\",\"type\":\"select\"}]");
        var missing = assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(noOptions));
        assertTrue(missing.getMessage().contains("comparison"));
        assertTrue(missing.getMessage().contains("options"));
        var duplicate = json.readTree("[{\"key\":\"comparison\",\"label\":\"比较方式\",\"type\":\"select\",\"options\":[\"一期\"]},{\"key\":\"comparison\",\"label\":\"比较方式\",\"type\":\"select\",\"options\":[\"一期\"]}]");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(duplicate))
                .getMessage().contains("key 重复: comparison"));
    }

    @Test void reasonsArePreservedAndCorrectiveItemsMustExplainWhy() throws Exception {
        var fields = ClarificationField.parse(json.readTree("""
                [{"key":"count","label":"期数","type":"number","reason":"invalid",
                  "message":"期数需要1～100，您填写的是200","value":200,"min":1,"max":100},
                 {"key":"base","label":"币种","type":"select","reason":"confirm",
                  "message":"您填写了两个币种，请选择本次比较的币种","value":"BTC和ETH","options":["BTC","ETH"]}]
                """));
        assertEquals("invalid", fields.get(0).reason());
        assertEquals("200", fields.get(0).value().asText());
        assertTrue(ClarificationField.question(fields).contains("您填写的是200"));
        assertEquals(fields, ClarificationField.parse(json.valueToTree(fields)));
        for (String reason : java.util.List.of("invalid", "confirm", "unknown")) {
            var node = json.readTree("[{\"key\":\"x\",\"label\":\"字段\",\"type\":\"text\",\"reason\":\"" + reason + "\"}]");
            assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(node));
        }
    }

    @Test void multiSelectBoundsAndPreviousSelectionsRoundTrip() throws Exception {
        var fields=ClarificationField.parse(json.readTree("""
                [{"key":"exchange","label":"交易所","type":"multiselect","options":["币安","OKX","第三家"],
                  "reason":"confirm","message":"请选择两家","minSelections":2,"maxSelections":2,"value":["币安","OKX"]}]
                """));
        assertEquals(2,fields.get(0).minSelections());
        assertEquals(2,fields.get(0).maxSelections());
        assertTrue(fields.get(0).value().isArray());
        assertEquals(fields,ClarificationField.parse(json.valueToTree(fields)));
        for (String bounds : java.util.List.of("\"minSelections\":0", "\"minSelections\":3,\"maxSelections\":2", "\"maxSelections\":4", "\"minSelections\":1.5")) {
            var node=json.readTree("[{\"key\":\"x\",\"label\":\"选择\",\"type\":\"multiselect\",\"options\":[\"A\",\"B\",\"C\"],"+bounds+"}]");
            assertThrows(IllegalArgumentException.class,()->ClarificationField.parse(node));
        }
        var single=json.readTree("[{\"key\":\"x\",\"label\":\"选择\",\"type\":\"select\",\"options\":[\"A\",\"B\"],\"minSelections\":2}]");
        assertThrows(IllegalArgumentException.class,()->ClarificationField.parse(single));
    }

    @Test void duplicateKeysAndOversizedListsAreRejected() throws Exception {
        var item = json.readTree("{\"key\":\"x\",\"label\":\"币种\",\"type\":\"text\"}");
        var list = json.createArrayNode().add(item).add(item);
        assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(list));
        for (int i = 0; i < 7; i++) list.add(item);
        assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(list));
    }
}
