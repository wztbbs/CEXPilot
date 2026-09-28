package com.cexpilot.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 集合最大值；并列取首个，index 为输入集合中的 0 基位置，kind=field 时附原始行 item。 */
@Component
public class MaxTool extends CalculationTool {
    @Override
    public String name() { return "max"; }

    @Override
    public void validateArguments(JsonNode args, boolean allowReferences) {
        Series.parse(args, allowReferences);
    }

    @Override
    protected JsonNode calculate(JsonNode args) {
        Series series = Series.parse(args, false);
        Series.Element best = null;
        for (Series.Element element : series.elements()) {
            if (best == null || element.value().compareTo(best.value()) > 0) best = element;
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("value", plain(best.value()));
        result.put("count", series.elements().size());
        result.put("index", best.index());
        if ("field".equals(series.kind())) result.set("item", best.row());
        result.put("method", "maximum");
        return result;
    }
}
