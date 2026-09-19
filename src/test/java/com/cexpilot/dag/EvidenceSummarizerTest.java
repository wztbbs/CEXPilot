package com.cexpilot.dag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvidenceSummarizer：已登记工具的明细字段被省略并标注 note；未登记工具按数组长度兜底；
 * 失败条目原样透传。
 */
class EvidenceSummarizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode entry(String tool, JsonNode data) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("node_id", "n1");
        entry.put("tool", tool);
        entry.put("ok", data != null);
        if (data != null) {
            entry.set("data", data);
        } else {
            entry.put("error", "交易所数据获取失败");
        }
        return entry;
    }

    private static ArrayNode evidenceOf(JsonNode... entries) {
        ArrayNode evidence = MAPPER.createArrayNode();
        for (JsonNode entry : entries) {
            evidence.add(entry);
        }
        return evidence;
    }

    @Test
    void klinesCandlesAreDroppedWithNote() {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("exchange", "OKX");
        data.put("symbol", "BTC");
        data.putObject("price_change").put("change_pct", 2.1);
        ArrayNode candles = data.putArray("candles");
        for (int i = 0; i < 288; i++) {
            candles.addArray().add(1L).add(2.0);
        }

        ArrayNode summary = EvidenceSummarizer.summarize(evidenceOf(entry("get_klines", data)));

        JsonNode projected = summary.get(0);
        JsonNode projectedData = projected.get("data");
        assertFalse(projectedData.has("candles"));
        assertTrue(projectedData.has("price_change"));
        assertEquals("BTC", projectedData.get("symbol").asText());
        assertTrue(projected.get("note").asText().contains("candles(288条)"));
    }

    @Test
    void unknownToolFallsBackToArraySize() {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("value", 1);
        data.putArray("small_list").add(1).add(2);
        ArrayNode big = data.putArray("big_list");
        for (int i = 0; i < 50; i++) {
            big.add(i);
        }

        ArrayNode summary = EvidenceSummarizer.summarize(evidenceOf(entry("some_new_tool", data)));

        JsonNode projectedData = summary.get(0).get("data");
        assertTrue(projectedData.has("small_list"));
        assertFalse(projectedData.has("big_list"));
        assertTrue(summary.get(0).get("note").asText().contains("big_list(50条)"));
    }

    @Test
    void failedEntryPassesThroughUnchanged() {
        ArrayNode summary = EvidenceSummarizer.summarize(evidenceOf(entry("get_ticker", null)));

        JsonNode projected = summary.get(0);
        assertFalse(projected.get("ok").asBoolean());
        assertEquals("交易所数据获取失败", projected.get("error").asText());
        assertFalse(projected.has("note"));
    }

    @Test
    void originalEvidenceIsNotMutated() {
        ObjectNode data = MAPPER.createObjectNode();
        data.putArray("candles").addArray().add(1L);

        ArrayNode evidence = evidenceOf(entry("get_klines", data));
        EvidenceSummarizer.summarize(evidence);

        assertTrue(evidence.get(0).get("data").has("candles"));
    }
}
