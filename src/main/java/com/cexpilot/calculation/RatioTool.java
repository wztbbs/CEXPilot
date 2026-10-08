package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/** 倍数或占比 left / right；分母 right 必须大于零，不取绝对值、不交换分子分母。 */
@Component
public class RatioTool extends CalculationTool {
    @Override
    public String name() { return "ratio"; }

    @Override
    public JsonNode planningInputSchema() { return CalculationInputSchemas.binary("left", "right"); }

    @Override
    public void validateArguments(JsonNode args, boolean allowReferences) {
        JsonNode input = input(args);
        object(input, "input", Set.of("left", "right"));
        decimal(input.get("left"), "left", allowReferences);
        BigDecimal right = decimal(input.get("right"), "right", allowReferences);
        if (right != null && right.signum() <= 0) {
            throw new IllegalArgumentException("right 必须大于 0；零或负分母不支持倍数或占比");
        }
    }

    @Override
    protected JsonNode calculate(JsonNode args) {
        JsonNode input = input(args);
        BigDecimal left = decimal(input.get("left"), "left", false);
        BigDecimal right = decimal(input.get("right"), "right", false);
        BigDecimal ratio = left.divide(right, PRECISION);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("left", plain(left));
        result.put("right", plain(right));
        result.put("value", plain(ratio));
        result.put("value_unit", "ratio");
        result.put("percent", plain(ratio.movePointRight(2)));
        result.put("formula", "left / right");
        return result;
    }
}
