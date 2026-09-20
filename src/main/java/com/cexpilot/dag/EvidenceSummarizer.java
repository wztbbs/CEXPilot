package com.cexpilot.dag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回答模型的输入压缩（确定性代码，不加 LLM 调用）：evidence 里的明细序列
 * （K线 candles、OI history、资金费率 recent_rates 等）在概览查询中通常冗余，
 * 却占输入 token 的绝大部分。这里按工具做字段投影——保留已计算好的指标与
 * 单位 / 时间口径 / 失败信息，丢弃明细数组，并在 note 字段标注省略了什么，
 * 避免模型误以为数据缺失；需要明细的节点保留全部返回数据，未知工具不裁剪。
 *
 * 完整 evidence 不受影响：仍随 ExecutionResult 返回前端、落 trace_event。
 */
public final class EvidenceSummarizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 概览回答默认省略的明细字段；include_details=true 时保留。 */
    private static final Map<String, List<String>> DETAIL_FIELDS = Map.of(
            "get_klines", List.of("candles"),
            "get_open_interest", List.of("history"),
            "get_funding_rate", List.of("recent_rates"),
            "get_orderbook", List.of("bids", "asks"),
            "get_recent_trades", List.of("recent_trades"));

    private EvidenceSummarizer() {
    }

    public static ArrayNode summarize(ArrayNode evidence) {
        return summarize(evidence, Set.of());
    }

    /** keepDetails 包含用户问题需要明细的节点 id；不改动原始 evidence。 */
    public static ArrayNode summarize(ArrayNode evidence, Set<String> keepDetails) {
        ArrayNode summary = MAPPER.createArrayNode();
        for (JsonNode entry : evidence) {
            ObjectNode projected = entry.deepCopy();
            if (!keepDetails.contains(projected.path("node_id").asText())
                    && projected.get("data") instanceof ObjectNode data) {
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

    /** 返回被省略的字段描述（如 "candles(288条)"）。未登记的字段不裁剪。 */
    private static List<String> project(ObjectNode data, List<String> detailFields) {
        List<String> omitted = new ArrayList<>();
        for (String field : detailFields) {
            JsonNode node = data.get(field);
            if (node instanceof ArrayNode array) {
                data.remove(field);
                data.remove(field + "_columns");
                omitted.add(field + "(" + array.size() + "条)");
            }
        }
        return omitted;
    }

}
