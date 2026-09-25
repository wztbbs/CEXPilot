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
 * EvidenceSummarizer：已登记工具的明细字段被省略并标注 note；未登记工具保留明细；
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
    void unknownToolKeepsLargeArrays() {
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
        assertTrue(projectedData.has("big_list"));
        assertFalse(summary.get(0).has("note"));
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
    @Test
    void requestedDetailsAreKeptOnlyForSelectedNode() {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode rates = data.putArray("rates");
        for (int i = 0; i < 20; i++) {
            rates.add(0.0001);
        }
        data.putArray("rates_columns").add("rate_fraction");
        ObjectNode first = entry("get_funding_rate_history", data);
        ObjectNode second = entry("get_funding_rate_history", data);
        second.put("node_id", "n2");
        ArrayNode result = EvidenceSummarizer.summarize(evidenceOf(first, second), java.util.Set.of("n1"));
        assertEquals(20, result.get(0).path("data").path("rates").size());
        assertFalse(result.get(1).path("data").has("rates"));
        assertTrue(result.get(0).path("data").has("rates_columns"));
        assertFalse(result.get(1).path("data").has("rates_columns"));
    }

    @Test
    void smallArraysAreAlwaysKept() {
        // 10 期资金费率这类小数组不占 token，不应被裁剪——否则回答模型会误以为数据缺失
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode rates = data.putArray("rates");
        for (int i = 0; i < 10; i++) {
            rates.add(0.0001);
        }

        ArrayNode summary = EvidenceSummarizer.summarize(evidenceOf(entry("get_funding_rate", data)));

        JsonNode projected = summary.get(0);
        assertEquals(10, projected.path("data").path("rates").size());
        assertFalse(projected.has("note"));
    }

    @Test
    void oiSeriesUsesCurrentFieldName() {
        // OI 序列字段改名后，摘要层要认新名字（大序列仍裁剪）
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode series = data.putArray("oi_series");
        for (int i = 0; i < 24; i++) {
            series.addArray().add("2026-09-20 00:00:00").add(1.0);
        }
        data.putArray("oi_series_columns").add("time").add("open_interest");

        ArrayNode summary = EvidenceSummarizer.summarize(evidenceOf(entry("get_open_interest_history", data)));

        JsonNode projected = summary.get(0);
        assertFalse(projected.path("data").has("oi_series"));
        assertFalse(projected.path("data").has("oi_series_columns"));
        assertTrue(projected.get("note").asText().contains("oi_series(24条)"));
    }
}
