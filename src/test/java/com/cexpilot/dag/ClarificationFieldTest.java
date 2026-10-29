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

    @Test void duplicateKeysAndOversizedListsAreRejected() throws Exception {
        var item = json.readTree("{\"key\":\"x\",\"label\":\"币种\",\"type\":\"text\"}");
        var list = json.createArrayNode().add(item).add(item);
        assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(list));
        for (int i = 0; i < 7; i++) list.add(item);
        assertThrows(IllegalArgumentException.class, () -> ClarificationField.parse(list));
    }
}
