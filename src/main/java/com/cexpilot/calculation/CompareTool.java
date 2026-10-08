package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/** 精确比较两个十进制数的大小关系，含相等；不做近似误差容忍。 */
@Component
public class CompareTool extends CalculationTool {
    @Override
    public String name() { return "compare"; }

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
        int sign = left.compareTo(right);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("left", plain(left));
        result.put("right", plain(right));
        result.put("relation", sign > 0 ? "greater" : sign < 0 ? "less" : "equal");
        result.put("value", Integer.toString(sign));
        result.put("value_unit", "comparison");
        result.put("formula", "sign(left - right)");
        return result;
    }
}
