package com.cexpilot.calculation;

import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class CalculationToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AvgTool avg = new AvgTool();
    private final RelativeChangeTool change = new RelativeChangeTool();

    private ToolResult execute(CalculationTool tool, String input) throws Exception {
        return tool.execute(MAPPER.readTree("{\"input\":" + input + "}"), null);
    }

    private void value(ToolResult result, String expected) {
        assertTrue(result.ok(), result::error);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(result.data().path("value").asText())));
    }

    @Test
    void averagesScalarsWithoutBinaryRoundingOrEarlySumRounding() throws Exception {
        value(execute(avg, """
                {"kind":"values","values":[0.1,"0.2"]}
                """), "0.15");
        value(execute(avg, """
                {"kind":"values","values":["9007199254740993","9007199254740995"]}
                """), "9007199254740994");
        value(execute(avg, """
                {"kind":"values","values":["1e40",1,"-1e40"]}
                """), "0.3333333333333333333333333333333333");
        value(execute(avg, """
                {"kind":"values","values":[-2,0,2]}
                """), "0");
        value(execute(avg, """
                {"kind":"values","values":["1e-8"]}
                """), "0.00000001");
    }

    @Test
    void projectsObjectFieldsAndTableColumnsUsingTheSameMean() throws Exception {
        ToolResult objects = execute(avg, """
                {"kind":"field","collection":[{"rate":"0.0001"},{"rate":"0.0003"}],"field":"rate"}
                """);
        ToolResult table = execute(avg, """
                {"kind":"field","collection":[["t1","0.0001"],["t2","0.0003"]],
                 "columns":["settle_time","rate"],"field":"rate"}
                """);
        value(objects, "0.0002");
        assertEquals(objects.data(), table.data());
        assertEquals(2, table.data().path("count").asInt());
        assertEquals("equal_weight_arithmetic_mean", table.data().path("method").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{\"kind\":\"unknown\"}",
            "{\"kind\":\"values\",\"values\":[]}",
            "{\"kind\":\"values\",\"values\":[1,null]}",
            "{\"kind\":\"values\",\"values\":[1,true]}",
            "{\"kind\":\"values\",\"values\":[[1,2]]}",
            "{\"kind\":\"values\",\"values\":[\"1%\"]}",
            "{\"kind\":\"values\",\"values\":[\"1,000\"]}",
            "{\"kind\":\"values\",\"values\":[\"1+2\"]}",
            "{\"kind\":\"values\",\"values\":[\"NaN\"]}",
            "{\"kind\":\"values\",\"values\":[\"Infinity\"]}",
            "{\"kind\":\"values\",\"values\":[\"1e999999999\"]}",
            "{\"kind\":\"values\",\"values\":[1],\"collection\":[]}",
            "{\"kind\":\"values\",\"values\":[1],\"skip_null\":true}",
            "{\"kind\":\"field\",\"collection\":[{\"x\":1},{}],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[{\"x\":null}],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[[1]],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[[1]],\"columns\":[\"y\"],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[[1,2]],\"columns\":[\"x\",\"x\"],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[[1]],\"columns\":[\"x\",\"y\"],\"field\":\"x\"}",
            "{\"kind\":\"field\",\"collection\":[{\"x\":1}],\"columns\":[\"x\"],\"field\":\"x\"}"
    })
    void rejectsAmbiguousOrIncompleteAverageInputs(String input) throws Exception {
        ToolResult result = execute(avg, input);
        assertFalse(result.ok());
        assertNull(result.data());
    }

    @Test
    void rejectsOversizeCollectionsAndUnknownTopLevelArguments() throws Exception {
        var args = MAPPER.createObjectNode();
        var input = args.putObject("input");
        input.put("kind", "values");
        var values = input.putArray("values");
        for (int i = 0; i < 10_001; i++) values.add(i);
        assertFalse(avg.execute(args, null).ok());
        values.removeAll().add(1);
        args.put("formula", "sum(values)");
        assertFalse(avg.execute(args, null).ok());
        assertFalse(avg.execute(null, null).ok());
    }

    @Test
    void relativeChangePreservesBaselineDirectionAndPercentageScale() throws Exception {
        ToolResult increase = execute(change, "{\"current\":120,\"baseline\":\"100\"}");
        value(increase, "0.2");
        assertEquals("20", increase.data().path("percent").asText());
        assertEquals("ratio", increase.data().path("value_unit").asText());
        value(execute(change, "{\"current\":80,\"baseline\":100}"), "-0.2");
        value(execute(change, "{\"current\":100,\"baseline\":100}"), "0");
        value(execute(change, "{\"current\":0,\"baseline\":100}"), "-1");
        value(execute(change, "{\"current\":-20,\"baseline\":100}"), "-1.2");
        value(execute(change, "{\"current\":\"0.0002\",\"baseline\":\"1e-4\"}"), "1");
        value(execute(change, "{\"current\":100,\"baseline\":120}"), "-0.1666666666666666666666666666666667");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{\"current\":1}", "{\"current\":1,\"baseline\":0}",
            "{\"current\":1,\"baseline\":-1}", "{\"current\":1,\"baseline\":\"-0.0\"}",
            "{\"current\":null,\"baseline\":1}", "{\"current\":[],\"baseline\":1}",
            "{\"current\":\"1%\",\"baseline\":1}", "{\"current\":1,\"baseline\":1,\"formula\":\"current/baseline\"}"
    })
    void rejectsUndefinedOrInvalidRelativeChangeInputs(String input) throws Exception {
        assertFalse(execute(change, input).ok());
    }

    @Test
    void referencesAreAcceptedOnlyDuringPlanning() throws Exception {
        var args = MAPPER.readTree("""
                {"input":{"kind":"values","values":["{{a.data.value}}","{{b.data.value}}"]}}
                """);
        assertDoesNotThrow(() -> avg.validateArguments(args, true));
        assertFalse(avg.execute(args, null).ok());
        var table = MAPPER.readTree("""
                {"input":{"kind":"field","collection":"{{a.data.rows}}","columns":"{{a.data.columns}}","field":"price"}}
                """);
        assertDoesNotThrow(() -> avg.validateArguments(table, true));
        var relative = MAPPER.readTree("""
                {"input":{"current":"{{a.data.value}}","baseline":"{{b.data.value}}"}}
                """);
        assertDoesNotThrow(() -> change.validateArguments(relative, true));
        assertFalse(change.execute(relative, null).ok());
    }
}
