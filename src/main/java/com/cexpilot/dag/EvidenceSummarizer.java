package com.cexpilot.dag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回答模型的输入压缩（确定性代码，不加 LLM 调用）：evidence 里的明细序列
 * （K线 candles、OI history、资金费率 recent_rates 等）对最终回答没有增量价值，
 * 却占输入 token 的绝大部分。这里按工具做字段投影——保留已计算好的指标与
 * 单位 / 时间口径 / 失败信息，丢弃明细数组，并在 note 字段标注省略了什么，
 * 避免模型误以为数据缺失。
 *
 * 完整 evidence 不受影响：仍随 ExecutionResult 返回前端、落 trace_event。
 */
public final class EvidenceSummarizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 未登记工具的兜底：数组超过该长度即视为明细序列，同样省略。 */
    private static final int MAX_KEEP_ARRAY_SIZE = 10;

    /** 各工具按条数膨胀、对回答无增量价值的明细字段。 */
    private static final Map<String, List<String>> DETAIL_FIELDS = Map.of(
            "get_klines", List.of("candles"),
            "get_open_interest", List.of("history"),
            "get_funding_rate", List.of("recent_rates"),
            "get_orderbook", List.of("bids", "asks"),
            "get_recent_trades", List.of("recent_trades"));

    private EvidenceSummarizer() {
    }

    public static ArrayNode summarize(ArrayNode evidence) {
        ArrayNode summary = MAPPER.createArrayNode();
        for (JsonNode entry : evidence) {
            ObjectNode projected = entry.deepCopy();
            if (projected.get("data") instanceof ObjectNode data) {
                String tool = projected.path("tool").asText("");
                List<String> omitted = project(data, DETAIL_FIELDS.getOrDefault(tool, List.of()));
                if (!omitted.isEmpty()) {
                    projected.put("note",
                            "明细序列已省略（" + String.join("、", omitted) + "），完整数据在执行记录中");
                }
            }
            summary.add(projected);
        }
        return summary;
    }

    /** 返回被省略的字段描述（如 "candles(288条)"）。detailFields 为空时按数组长度兜底。 */
    private static List<String> project(ObjectNode data, List<String> detailFields) {
        List<String> omitted = new ArrayList<>();
        List<String> candidates = detailFields.isEmpty() ? oversizedArrays(data) : detailFields;
        for (String field : candidates) {
            JsonNode node = data.get(field);
            if (node instanceof ArrayNode array) {
                data.remove(field);
                omitted.add(field + "(" + array.size() + "条)");
            }
        }
        return omitted;
    }

    private static List<String> oversizedArrays(ObjectNode data) {
        List<String> fields = new ArrayList<>();
        data.fields().forEachRemaining(field -> {
            if (field.getValue() instanceof ArrayNode array && array.size() > MAX_KEEP_ARRAY_SIZE) {
                fields.add(field.getKey());
            }
        });
        return fields;
    }
}
