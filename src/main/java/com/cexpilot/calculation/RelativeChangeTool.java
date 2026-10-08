package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/** 基准必须大于零；不对负基准取绝对值，也不交换分子分母。 */
@Component
public class RelativeChangeTool extends CalculationTool {
    @Override
    public String name() { return "relative_change"; }

    @Override
    public JsonNode planningInputSchema() { return CalculationInputSchemas.binary("current", "baseline"); }

    @Override
    public void validateArguments(JsonNode args, boolean allowReferences) {
        JsonNode input = input(args);
        if (input.has("left") || input.has("right")) {
            throw new IllegalArgumentException("relative_change 的 input 必须使用 {current, baseline}，"
                    + "例如 {\"current\":\"{{m1.binance.value}}\",\"baseline\":\"{{m2.okx.value}}\"}，不要写成 left/right");
        }
        object(input, "input", Set.of("current", "baseline"));
        decimal(input.get("current"), "current", allowReferences);
        BigDecimal baseline = decimal(input.get("baseline"), "baseline", allowReferences);
        if (baseline != null && baseline.signum() <= 0) {
            throw new IllegalArgumentException("baseline 必须大于 0；零或负基准不支持相对变化率");
        }
    }

    @Override
    protected JsonNode calculate(JsonNode args) {
        JsonNode input = input(args);
        BigDecimal current = decimal(input.get("current"), "current", false);
        BigDecimal baseline = decimal(input.get("baseline"), "baseline", false);
        BigDecimal ratio = current.subtract(baseline).divide(baseline, PRECISION);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("current", plain(current));
        result.put("baseline", plain(baseline));
        result.put("value", plain(ratio));
        result.put("value_unit", "ratio");
        result.put("percent", plain(ratio.movePointRight(2)));
        result.put("formula", "(current - baseline) / baseline");
        return result;
    }
}
