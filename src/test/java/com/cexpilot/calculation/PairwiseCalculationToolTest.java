package com.cexpilot.calculation;

import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PairwiseCalculationToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CompareTool compare = new CompareTool();
    private final DifferenceTool difference = new DifferenceTool();
    private final RatioTool ratio = new RatioTool();

    private ToolResult execute(CalculationTool tool, String input) throws Exception {
        return tool.execute(MAPPER.readTree("{\"input\":" + input + "}"), null);
    }

    private void value(ToolResult result, String expected) {
        assertTrue(result.ok(), result::error);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(result.data().path("value").asText())));
    }

    @Test
    void compareReportsGreaterLessAndEqualExactly() throws Exception {
        ToolResult greater = execute(compare, "{\"left\":120,\"right\":\"100\"}");
        assertEquals("greater", greater.data().path("relation").asText());
        value(greater, "1");
        assertEquals("120", greater.data().path("left").asText());
        assertEquals("100", greater.data().path("right").asText());
        ToolResult less = execute(compare, "{\"left\":\"0.09999999999999999999\",\"right\":0.1}");
        assertEquals("less", less.data().path("relation").asText());
        value(less, "-1");
        ToolResult equal = execute(compare, "{\"left\":\"1.00\",\"right\":1}");
        assertEquals("equal", equal.data().path("relation").asText());
        value(equal, "0");
        assertEquals("comparison", equal.data().path("value_unit").asText());
        assertEquals("less", execute(compare, "{\"left\":-5,\"right\":0}").data().path("relation").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{\"left\":1}", "{\"left\":null,\"right\":1}", "{\"left\":[],\"right\":1}",
            "{\"left\":\"1%\",\"right\":1}", "{\"left\":\"1,000\",\"right\":1}", "{\"left\":true,\"right\":1}",
            "{\"left\":1,\"right\":1,\"formula\":\"left-right\"}"
    })
    void rejectsInvalidCompareInputs(String input) throws Exception {
        ToolResult result = execute(compare, input);
        assertFalse(result.ok());
        assertNull(result.data());
    }

    @Test
    void differenceKeepsSignAndInputUnit() throws Exception {
        ToolResult positive = execute(difference, "{\"left\":\"43210.5\",\"right\":43000}");
        value(positive, "210.5");
        assertEquals("same_as_inputs", positive.data().path("value_unit").asText());
        value(execute(difference, "{\"left\":100,\"right\":120}"), "-20");
        value(execute(difference, "{\"left\":0.3,\"right\":0.1}"), "0.2");
        value(execute(difference, "{\"left\":5,\"right\":15}"), "-10");
        value(execute(difference, "{\"left\":\"1e-8\",\"right\":\"2e-8\"}"), "-0.00000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{\"right\":1}", "{\"left\":1,\"right\":null}", "{\"left\":\"1+2\",\"right\":1}",
            "{\"left\":1,\"right\":1,\"round\":2}"
    })
    void rejectsInvalidDifferenceInputs(String input) throws Exception {
        assertFalse(execute(difference, input).ok());
    }

    @Test
    void ratioPreservesDenominatorDirectionAndPercentageScale() throws Exception {
        ToolResult triple = execute(ratio, "{\"left\":300,\"right\":\"100\"}");
        value(triple, "3");
        assertEquals("300", triple.data().path("percent").asText());
        assertEquals("ratio", triple.data().path("value_unit").asText());
        ToolResult share = execute(ratio, "{\"left\":1,\"right\":3}");
        value(share, "0.3333333333333333333333333333333333");
        assertEquals("33.33333333333333333333333333333333", share.data().path("percent").asText());
        value(execute(ratio, "{\"left\":0,\"right\":100}"), "0");
        value(execute(ratio, "{\"left\":-50,\"right\":100}"), "-0.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{\"left\":1}", "{\"left\":1,\"right\":0}", "{\"left\":1,\"right\":-1}",
            "{\"left\":1,\"right\":\"-0.0\"}", "{\"left\":null,\"right\":1}", "{\"left\":[],\"right\":1}",
            "{\"left\":\"1%\",\"right\":1}", "{\"left\":1,\"right\":1,\"formula\":\"right/left\"}"
    })
    void rejectsUndefinedOrInvalidRatioInputs(String input) throws Exception {
        assertFalse(execute(ratio, input).ok());
    }

    @Test
    void referencesAreAcceptedOnlyDuringPlanning() throws Exception {
        for (CalculationTool tool : new CalculationTool[]{compare, difference, ratio}) {
            var args = MAPPER.readTree("""
                    {"input":{"left":"{{a.data.value}}","right":"{{b.data.value}}"}}""");
            assertDoesNotThrow(() -> tool.validateArguments(args, true));
            assertFalse(tool.execute(args, null).ok());
        }
    }
}
