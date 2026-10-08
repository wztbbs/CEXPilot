package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/** 带符号差值 left - right，与输入同单位；输入为 percent 时结果是百分点差。 */
@Component
public class DifferenceTool extends CalculationTool {
    @Override
    public String name() { return "difference"; }

    @Override
    public JsonNode planningInputSchema() { return CalculationInputSchemas.binary("left", "right"); }

    @Override
    public void validateArguments(JsonNode args, boolean allowReferences) {
        JsonNode input = input(args);
        object(input, "input", Set.of("left", "right"));
        decimal(input.get("left"), "left", allowReferences);
        decimal(input.get("right"), "right", allowReferences);
    }

    @Override
    protected JsonNode calculate(JsonNode args) {
        JsonNode input = input(args);
        BigDecimal left = decimal(input.get("left"), "left", false);
        BigDecimal right = decimal(input.get("right"), "right", false);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("left", plain(left));
        result.put("right", plain(right));
        result.put("value", plain(left.subtract(right)));
        result.put("value_unit", "same_as_inputs");
        result.put("formula", "left - right");
        return result;
    }
}
