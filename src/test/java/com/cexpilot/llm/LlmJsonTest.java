package com.cexpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmJsonTest {

    @Test
    void parsePlainJson() {
        JsonNode node = LlmJson.parse("{\"a\": 1}");
        assertEquals(1, node.get("a").asInt());
    }

    @Test
    void stripCodeFence() {
        JsonNode node = LlmJson.parse("```json\n{\"a\": 2}\n```");
        assertEquals(2, node.get("a").asInt());
    }

    @Test
    void extractFromSurroundingText() {
        JsonNode node = LlmJson.parse("好的，结果是 {\"a\": 3} 这样");
        assertEquals(3, node.get("a").asInt());
    }

    @Test
    void emptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> LlmJson.parse(null));
        assertThrows(IllegalArgumentException.class, () -> LlmJson.parse("没有 JSON"));
    }
}
