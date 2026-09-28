package com.cexpilot.calculation;

import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class SeriesCalculationToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SumTool sum = new SumTool();
    private final MinTool min = new MinTool();
    private final MaxTool max = new MaxTool();

    private ToolResult execute(CalculationTool tool, String input) throws Exception {
        return tool.execute(MAPPER.readTree("{\"input\":" + input + "}"), null);
    }

    private void value(ToolResult result, String expected) {
        assertTrue(result.ok(), result::error);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(result.data().path("value").asText())));
    }

    @Test
    void sumsScalarsExactlyWithoutBinaryRounding() throws Exception {
        value(execute(sum, """
                {"kind":"values","values":[0.1,"0.2"]}
                """), "0.3");
        value(execute(sum, """
                {"kind":"values","values":["9007199254740993","9007199254740995"]}
                """), "18014398509481988");
        value(execute(sum, """
                {"kind":"values","values":["1e40",1,"-1e40"]}
                """), "1");
        value(execute(sum, """
                {"kind":"values","values":[-2,0,2]}
                """), "0");
        ToolResult single = execute(sum, """
                {"kind":"values","values":["1e-8"]}
                """);
        value(single, "0.00000001");
        assertEquals(1, single.data().path("count").asInt());
        assertEquals("arithmetic_sum", single.data().path("method").asText());
    }

    @Test
    void sumsObjectFieldsAndTableColumns() throws Exception {
        ToolResult objects = execute(sum, """
                {"kind":"field","collection":[{"rate":"0.0001"},{"rate":"0.0003"}],"field":"rate"}
                """);
        ToolResult table = execute(sum, """
                {"kind":"field","collection":[["t1","0.0001"],["t2","0.0003"]],
                 "columns":["settle_time","rate"],"field":"rate"}
                """);
        value(objects, "0.0004");
        assertEquals(objects.data(), table.data());
    }

    @Test
    void findsMinimumAndMaximumWithFirstIndex() throws Exception {
        ToolResult lowest = execute(min, """
                {"kind":"values","values":["0.0003","0.0001","0.0002","0.0001"]}
                """);
        value(lowest, "0.0001");
        assertEquals(1, lowest.data().path("index").asInt());
        assertEquals(4, lowest.data().path("count").asInt());
        assertEquals("minimum", lowest.data().path("method").asText());
        assertFalse(lowest.data().has("item"));

        ToolResult highest = execute(max, """
                {"kind":"values","values":["0.0003","0.0005","0.0005","0.0001"]}
                """);
        value(highest, "0.0005");
        assertEquals(1, highest.data().path("index").asInt());
        assertEquals("maximum", highest.data().path("method").asText());

        value(execute(min, """
                {"kind":"values","values":[-5,0,3]}
                """), "-5");
        value(execute(max, """
                {"kind":"values","values":[-5,0,3]}
                """), "3");
    }

    @Test
    void minMaxReturnSourceRowForFieldCollections() throws Exception {
        ToolResult table = execute(min, """
                {"kind":"field","collection":[["2024-01-01","0.0003"],["2024-01-02","0.0001"],["2024-01-03","0.0002"]],
                 "columns":["settle_time","rate"],"field":"rate"}
                """);
        value(table, "0.0001");
        assertEquals(1, table.data().path("index").asInt());
        assertEquals("2024-01-02", table.data().path("item").get(0).asText());
        ToolResult objects = execute(max, """
                {"kind":"field","collection":[{"exchange":"binance","rate":"0.0001"},{"exchange":"okx","rate":"0.0003"}],"field":"rate"}
                """);
        value(objects, "0.0003");
        assertEquals(1, objects.data().path("index").asInt());
        assertEquals("okx", objects.data().path("item").path("exchange").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{\"kind\":\"unknown\"}",
            "{\"kind\":\"values\",\"values\":[]}",
            "{\"kind\":\"values\",\"values\":[1,null]}",
            "{\"kind\":\"values\",\"values\":[1,true]}",
            "{\"kind\":\"values\",\"values\":[\"1%\"]}",
            "{\"kind\":\"values\",\"values\":[\"1,000\"]}",
            "{\"kind\":\"values\",\"values\":[\"NaN\"]}",
            "{\"kind\":\"values\",\"values\":[1],\"order\":\"asc\"}",
            "{\"kind\":\"field\",\"collection\":[{\"x\":1},{}],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[[1]],\"columns\":[\"y\"],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[[1,2]],\"columns\":[\"x\",\"x\"],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[[1]],\"columns\":[\"x\",\"y\"],\"field\":\"x\"}"
    })
    void rejectsAmbiguousOrIncompleteSeriesInputs(String input) throws Exception {
        for (CalculationTool tool : new CalculationTool[]{sum, min, max}) {
            ToolResult result = execute(tool, input);
            assertFalse(result.ok());
            assertNull(result.data());
        }
    }

    @Test
    void referencesAreAcceptedOnlyDuringPlanning() throws Exception {
        for (CalculationTool tool : new CalculationTool[]{sum, min, max}) {
            var args = MAPPER.readTree("""
                    {"input":{"kind":"values","values":["{{a.data.value}}","{{b.data.value}}"]}}""");
            assertDoesNotThrow(() -> tool.validateArguments(args, true));
            assertFalse(tool.execute(args, null).ok());
            var table = MAPPER.readTree("""
                    {"input":{"kind":"field","collection":"{{a.data.rows}}","columns":"{{a.data.columns}}","field":"price"}}""");
            assertDoesNotThrow(() -> tool.validateArguments(table, true));
        }
    }
}
