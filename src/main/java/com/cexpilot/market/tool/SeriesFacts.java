package com.cexpilot.market.tool;

import com.cexpilot.market.Times;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.util.List;

/**
 * 历史序列类 tool 共享的 facts 片段：请求/生效区间与覆盖核对的 JSON 结构，
 * 保证 get_klines、get_market_statistics 等返回口径一致。
 */
final class SeriesFacts {

    private static final int OPEN_TIME_PREVIEW_LIMIT = 20;

    private SeriesFacts() {
    }

    static ObjectNode rangeJson(TimeRange range) {
        ObjectNode node = AbstractMarketTool.MAPPER.createObjectNode();
        node.put("start_inclusive", Times.readable(range.startInclusive().toEpochMilli(), range.timezone()));
        node.put("end_exclusive", Times.readable(range.endExclusive().toEpochMilli(), range.timezone()));
        node.put("timezone", range.timezone().getId());
        return node;
    }

    static ObjectNode coverageJson(SeriesCoverage coverage, ZoneId zone) {
        ObjectNode node = AbstractMarketTool.MAPPER.createObjectNode();
        node.put("expected_count", coverage.expectedCount());
        node.put("actual_count", coverage.actualCount());
        node.put("closed_part_complete", coverage.complete());
        node.put("range_complete", coverage.rangeComplete());
        if (coverage.coveredUntilMs() != null) {
            node.put("covered_until", Times.readable(coverage.coveredUntilMs(), zone));
        }
        node.put("missing_count", coverage.missing().size());
        addOpenTimes(node, "missing_open_times", coverage.missing(), zone);
        node.put("unexpected_count", coverage.unexpected().size());
        addOpenTimes(node, "unexpected_open_times", coverage.unexpected(), zone);
        node.put("contains_unclosed", coverage.containsUnclosed());
        node.put("dropped_unclosed", coverage.droppedUnclosed());
        if (coverage.abortReason() != null) {
            node.put("abort_reason", coverage.abortReason());
        }
        return node;
    }

    private static void addOpenTimes(ObjectNode node, String field, List<Long> openTimes, ZoneId zone) {
        ArrayNode array = node.putArray(field);
        openTimes.stream().limit(OPEN_TIME_PREVIEW_LIMIT)
                .forEach(t -> array.add(Times.readable(t, zone)));
    }
}
