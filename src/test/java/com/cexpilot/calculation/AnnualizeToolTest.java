package com.cexpilot.calculation;

import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AnnualizeToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AnnualizeTool tool = new AnnualizeTool();

    private ObjectNode args(String basis, String method, String rate, String rateUnit, String duration, String durationUnit) {
        ObjectNode args = MAPPER.createObjectNode();
        ObjectNode input = args.putObject("input");
        input.put("basis", basis).put("method", method).put("rate", rate).put("rate_unit", rateUnit);
        input.putObject("period").put("value", duration).put("unit", durationUnit);
        return args;
    }

    private JsonNode success(JsonNode args) {
        ToolResult result = tool.execute(args, null);
        assertTrue(result.ok(), result::error);
        return result.data();
    }

    private void number(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())), actual::toString);
    }

    @Test
    void periodicRateUsesActualPeriodAndNormalizesPercentageExactlyOnce() {
        var ratio = args("periodic_rate", "simple", "0.0001", "ratio", "8", "hour");
        JsonNode data = success(ratio);
        number("0.1095", data.get("value"));
        number("10.95", data.get("percent"));
        number("1095", data.get("annual_factor"));
        number("28800", data.get("period_seconds"));
        assertEquals(365, data.path("year_days").asInt());
        assertEquals("default", data.path("year_days_source").asText());
        assertEquals("ratio", data.path("value_unit").asText());
        var percent = args("periodic_rate", "simple", "0.01", "percent", "8", "hour");
        assertEquals(data.get("value"), success(percent).get("value"));
        number("0.219", success(args("periodic_rate", "simple", "0.0001", "ratio", "4", "hour")).get("value"));
        number("-0.1095", success(args("periodic_rate", "simple", "-0.0001", "ratio", "8", "hour")).get("value"));
    }

    @Test
    void historicalSumUsesWholeWindowAndExplicitYearConvention() {
        ObjectNode inputArgs = args("cumulative_rate", "simple", "0.007", "ratio", "604800", "second");
        JsonNode data = success(inputArgs);
        number("0.365", data.get("value"));
        number("36.5", data.get("percent"));
        assertEquals("cumulative_rate", data.path("basis").asText());
        ObjectNode input = (ObjectNode) inputArgs.get("input");
        for (int days : new int[]{360, 366}) {
            input.put("year_days", days);
            JsonNode changed = success(inputArgs);
            number(BigDecimal.valueOf(days, 3).toPlainString(), changed.get("value"));
            assertEquals("explicit", changed.path("year_days_source").asText());
        }
    }

    @Test
    void compoundSupportsFractionalExponentsWithoutDoubleArithmetic() {
        number("0.21", success(args("holding_return", "compound", "10", "percent", "182.5", "day")).get("value"));
        number("0.1", success(args("holding_return", "compound", "21", "percent", "730", "day")).get("value"));
        // 独立以 Python decimal、70 位精度求得的基准，365/7 不是整数。
        number("0.6800754114925196540001204206571523",
                success(args("holding_return", "compound", "0.01", "ratio", "7", "day")).get("value"));
        number("0.1157139627916866356545482790508672",
                success(args("periodic_rate", "compound", "0.0001", "ratio", "8", "hour")).get("value"));
        number("-0.1150986297390481630178610480100143",
                success(args("holding_return", "compound", "-0.01", "ratio", "30", "day")).get("value"));
    }

    @Test
    void zeroTotalLossAndTinyRatesAreNotCorruptedByCancellation() {
        number("0", success(args("periodic_rate", "compound", "0", "ratio", "8", "hour")).get("value"));
        number("-1", success(args("holding_return", "compound", "-100", "percent", "7", "day")).get("value"));
        number("1e-40", success(args("holding_return", "compound", "1e-40", "ratio", "365", "day")).get("value"));
        number("1.095e-37", success(args("periodic_rate", "compound", "1e-40", "ratio", "8", "hour")).get("value"));
        // 线性费率累计不是账户总收益，允许低于 -100%。
        number("-2", success(args("cumulative_rate", "simple", "-2", "ratio", "365", "day")).get("value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"basis", "method", "rate", "rate_unit", "period"})
    void requiredBusinessChoicesHaveNoSilentDefaults(String field) {
        var args = args("periodic_rate", "simple", "0.0001", "ratio", "8", "hour");
        ((ObjectNode) args.get("input")).remove(field);
        assertFalse(tool.execute(args, null).ok());
        assertThrows(IllegalArgumentException.class, () -> tool.validateArguments(args, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.5", "31557600001", "1e999", "null", "NaN", "{{missing}}"})
    void invalidDurationsFail(String duration) {
        assertFalse(tool.execute(args("periodic_rate", "simple", "0.001", "ratio", duration, "second"), null).ok());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "252", "365.5", "\"365\"", "2147483648"})
    void rejectsInvalidYearDays(String days) throws Exception {
        var args = args("periodic_rate", "simple", "0.001", "ratio", "8", "hour");
        ((ObjectNode) args.get("input")).set("year_days", MAPPER.readTree(days));
        assertFalse(tool.execute(args, null).ok());
    }

    @Test
    void incompatibleSemanticsAndUnboundedCompoundingFail() {
        for (ObjectNode invalid : new ObjectNode[]{
                args("cumulative_rate", "compound", "0.01", "ratio", "7", "day"),
                args("holding_return", "simple", "-1.01", "ratio", "365", "day"),
                args("periodic_rate", "compound", "-1.01", "ratio", "8", "hour"),
                args("periodic_rate", "compound", "1", "ratio", "1", "second"),
                args("periodic_rate", "compound", "-0.5", "ratio", "1", "second"),
                args("periodic_rate", "simple", "0.01", "fraction", "1", "day"),
                args("periodic_rate", "simple", "0.01", "ratio", "1", "month"),
                args("periodic_rate", "simple", "0.01%", "percent", "1", "day"),
                args("guessed", "simple", "0.01", "ratio", "1", "day")}) {
            assertFalse(tool.execute(invalid, null).ok(), invalid::toString);
        }
        var args = args("periodic_rate", "simple", "0.01", "ratio", "1", "day");
        ((ObjectNode) args.get("input")).put("formula", "rate * 365");
        assertFalse(tool.execute(args, null).ok());
    }

    @Test
    void rateAndDurationReferencesAreDeferredButMethodMustBeExplicit() {
        var args = args("cumulative_rate", "simple", "{{s.data.statistics.sum}}", "ratio",
                "{{s.data.statistics.observation_seconds}}", "second");
        assertDoesNotThrow(() -> tool.validateArguments(args, true));
        assertFalse(tool.execute(args, null).ok());
        ((ObjectNode) args.get("input")).put("method", "{{s.data.method}}");
        assertThrows(IllegalArgumentException.class, () -> tool.validateArguments(args, true));
    }
}
