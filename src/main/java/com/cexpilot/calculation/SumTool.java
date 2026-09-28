package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 标量集合、对象字段或表格列统一绑定为数值集合，再精确求和（无舍入）。 */
@Component
public class SumTool extends CalculationTool {
    @Override
    public String name() { return "sum"; }

    @Override
    public void validateArguments(JsonNode args, boolean allowReferences) {
        Series.parse(args, allowReferences);
    }

    @Override
    protected JsonNode calculate(JsonNode args) {
        List<BigDecimal> values = Series.parse(args, false).values();
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("value", plain(sum));
        result.put("count", values.size());
        result.put("method", "arithmetic_sum");
        return result;
    }
}
