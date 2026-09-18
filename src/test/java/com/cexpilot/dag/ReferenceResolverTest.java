package com.cexpilot.dag;

import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReferenceResolver：全串引用保持 JSON 类型、嵌入式引用按文本插值、引用缺失报错。
 */
class ReferenceResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DagContext contextWith(String nodeId, String dataJson) {
        DagContext ctx = new DagContext();
        ctx.put(nodeId, ToolResult.success(json(dataJson)));
        return ctx;
    }

    @Test
    void fullStringRefKeepsJsonType() {
        DagContext ctx = contextWith("n1", "{\"price\": 42000.5, \"tags\": [\"a\", \"b\"]}");

        JsonNode resolved = ReferenceResolver.resolve(
                json("{\"cost\": \"{{n1.data.price}}\", \"tags\": \"{{n1.data.tags}}\"}"), ctx);

        // 全串匹配：数字仍是数字、数组仍是数组，不被字符串化
        assertTrue(resolved.get("cost").isNumber());
        assertEquals(42000.5, resolved.get("cost").asDouble());
        assertTrue(resolved.get("tags").isArray());
        assertEquals("b", resolved.get("tags").get(1).asText());
    }

    @Test
    void embeddedRefInterpolatesAsText() {
        DagContext ctx = contextWith("n1", "{\"symbol\": \"BTC\", \"change_pct\": 2.5}");

        JsonNode resolved = ReferenceResolver.resolve(
                json("{\"note\": \"币种 {{n1.data.symbol}} 涨了 {{n1.data.change_pct}}%\"}"), ctx);

        assertEquals("币种 BTC 涨了 2.5%", resolved.get("note").asText());
    }

    @Test
    void arrayIndexPathResolves() {
        DagContext ctx = contextWith("n1", "{\"candles\": [{\"close\": 100}, {\"close\": 200}]}");

        JsonNode resolved = ReferenceResolver.resolve(
                json("{\"close\": \"{{n1.data.candles.1.close}}\"}"), ctx);

        assertEquals(200, resolved.get("close").asInt());
    }

    @Test
    void missingNodeThrows() {
        DagContext ctx = new DagContext();
        assertThrows(ReferenceResolver.ReferenceResolutionException.class,
                () -> ReferenceResolver.resolve(json("{\"x\": \"{{n9.data.price}}\"}"), ctx));
    }

    @Test
    void missingPathThrows() {
        DagContext ctx = contextWith("n1", "{\"price\": 1}");
        assertThrows(ReferenceResolver.ReferenceResolutionException.class,
                () -> ReferenceResolver.resolve(json("{\"x\": \"{{n1.data.nope}}\"}"), ctx));
    }

    @Test
    void failedUpstreamThrows() {
        DagContext ctx = new DagContext();
        ctx.put("n1", ToolResult.failure("boom"));
        assertThrows(ReferenceResolver.ReferenceResolutionException.class,
                () -> ReferenceResolver.resolve(json("{\"x\": \"{{n1.data.price}}\"}"), ctx));
    }

    @Test
    void nonRefTextPassesThrough() {
        DagContext ctx = new DagContext();
        JsonNode resolved = ReferenceResolver.resolve(
                json("{\"symbol\": \"BTCUSDT\", \"limit\": 10}"), ctx);
        assertEquals("BTCUSDT", resolved.get("symbol").asText());
        assertEquals(10, resolved.get("limit").asInt());
    }
}
