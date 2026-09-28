package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 标量集合、对象字段或表格列统一绑定为数值集合，再做等权算术平均。 */
@Component
public class AvgTool extends CalculationTool {
    @Override
    public String name() { return "avg"; }

    @Override
    public void validateArguments(JsonNode args, boolean allowReferences) {
        Series.parse(args, allowReferences);
    }

    @Override
    protected JsonNode calculate(JsonNode args) {
        List<BigDecimal> values = Series.parse(args, false).values();
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("value", plain(sum.divide(BigDecimal.valueOf(values.size()), PRECISION)));
        result.put("count", values.size());
        result.put("method", "equal_weight_arithmetic_mean");
        return result;
    }
}
