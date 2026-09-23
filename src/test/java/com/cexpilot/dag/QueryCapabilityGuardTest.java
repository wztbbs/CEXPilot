package com.cexpilot.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryCapabilityGuardTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static ObjectNode requirements(String mode, String duration) {
        ObjectNode r = MAPPER.createObjectNode().put("time_scope", mode);
        r.put("duration", duration);
        r.putNull("sample_count");
        r.putNull("quote_asset");
        r.putNull("market_type");
        return r;
    }

    private DagPlan plan(String... tools) {
        return new DagPlan(java.util.Arrays.stream(tools).map(tool -> new PlanNode(tool, tool,
                MAPPER.createObjectNode().put("symbol", "BTC"), List.of(), false)).toList());
    }

    private DagPlan planWithCount(String tool, String argName, int value) {
        return new DagPlan(List.of(new PlanNode(tool, tool,
                MAPPER.createObjectNode().put("symbol", "BTC").put(argName, value), List.of(), false)));
    }

    @Test
    void historicalRequirementsRefusedEvenIfPlannerChoosesCurrentTicker() {
        for (String mode : List.of("calendar_window", "absolute_range", "period_comparison", "mixed", "unknown")) {
            assertNotNull(QueryCapabilityGuard.refusal("那段时间呢？", requirements(mode, null), plan("get_ticker")), mode);
        }
    }

    @Test
    void commonExplicitRequestsCannotBeErasedByDefaultPlan() {
        for (String question : List.of("拿币安 BTC-USDC 上周总交易额跟上上周对比",
                "BTC 今天涨了多少", "BTC 2026-09-01 到 2026-09-02 成交量",
                "BTC 10:00 到 11:00 的成交量", "BTC 成交量同比", "BTC volume last week",
                "BTC 过去6小时费率", "BTC 最近10期费率", "BTC-USDC 当前价格")) {
            assertNotNull(QueryCapabilityGuard.refusal(question, requirements("unspecified", null), plan("get_ticker")), question);
        }
    }

    @Test
    void rolling24hOnlyAdmitsTickerAndCannotMaskOtherDuration() {
        ObjectNode r = requirements("rolling_window", "24h");
        assertNull(QueryCapabilityGuard.refusal("币安 BTC 最近24小时成交量", r, plan("get_ticker")));
        assertNull(QueryCapabilityGuard.refusal("BTC last 24 hours volume", r, plan("get_ticker")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC 过去6小时成交量", r, plan("get_ticker")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC 过去24小时费率", r, plan("get_funding_rate")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC 过去24小时逐笔成交", r, plan("get_recent_trades")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC 过去24小时费率和涨跌", r, plan("get_ticker", "get_funding_rate")));
    }

    @Test
    void unverifiedWindowToolsRemainClosedEvenWithoutExplicitTime() {
        for (String tool : List.of("get_klines", "get_open_interest", "compare_exchanges")) {
            assertNotNull(QueryCapabilityGuard.refusal("BTC 情况", requirements("unspecified", null), plan(tool)), tool);
        }
    }

    @Test
    void currentSnapshotsAndUnboundedRecentSamplesRemainAvailable() {
        for (String tool : List.of("get_ticker", "get_orderbook", "get_mark_price", "get_funding_rate")) {
            assertNull(QueryCapabilityGuard.refusal("BTC 当前情况", requirements("current", null), plan(tool)), tool);
        }
        for (String tool : List.of("get_funding_rate", "get_recent_trades")) {
            assertNull(QueryCapabilityGuard.refusal("BTC 近期样本", requirements("recent_samples", null), plan(tool)), tool);
            // 有条数诉求但工具参数未显式携带条数（漏提取）→ 拒
            assertNotNull(QueryCapabilityGuard.refusal("BTC 近期样本", requirements("recent_samples", null)
                    .put("sample_count", 10), plan(tool)), tool);
        }
        assertNull(QueryCapabilityGuard.refusal("查询这笔交易", requirements("unspecified", null), plan("get_transaction")));
    }

    @Test
    void explicitSampleCountWithinLimitIsHonored() {
        // 条数显式落入工具参数且自报一致 → 放行
        assertNull(QueryCapabilityGuard.refusal("BTC 最近10期费率",
                requirements("recent_samples", null).put("sample_count", 10),
                planWithCount("get_funding_rate", "count", 10)));
        assertNull(QueryCapabilityGuard.refusal("BTC 最近30笔成交",
                requirements("recent_samples", null).put("sample_count", 30),
                planWithCount("get_recent_trades", "limit", 30)));
        // 问题里有条数、args 也有条数，仅 requirements 未填（复合诉求场景）→ 放行
        assertNull(QueryCapabilityGuard.refusal("BTC 最近30笔成交",
                requirements("recent_samples", null),
                planWithCount("get_recent_trades", "limit", 30)));
        // 超过上限 → 拒，文案带上限
        String over = QueryCapabilityGuard.refusal("BTC 最近500笔成交",
                requirements("recent_samples", null).put("sample_count", 500),
                planWithCount("get_recent_trades", "limit", 500));
        assertNotNull(over);
        assertTrue(over.contains("100"), over);
        // 自报条数与工具参数不一致 → 拒
        assertNotNull(QueryCapabilityGuard.refusal("BTC 最近10期费率",
                requirements("recent_samples", null).put("sample_count", 10),
                planWithCount("get_funding_rate", "count", 20)));
        // 条数诉求未按近期样本口径规划 → 拒
        assertNotNull(QueryCapabilityGuard.refusal("BTC 最近10期费率",
                requirements("current", null), planWithCount("get_funding_rate", "count", 10)));
    }

    @Test
    void missingMalformedOrInconsistentRequirementsFailClosed() {
        assertNotNull(QueryCapabilityGuard.refusal("BTC", MAPPER.missingNode(), plan("get_ticker")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC", MAPPER.nullNode(), plan("get_ticker")));
        ObjectNode r = requirements("current", null);
        r.remove("duration");
        assertNotNull(QueryCapabilityGuard.refusal("BTC", r, plan("get_ticker")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC", requirements("current", "24h"), plan("get_ticker")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC", requirements("current", null).put("sample_count", "ten"), plan("get_ticker")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC", requirements("current", null).put("market_type", "spot"), plan("get_ticker")));
        assertNotNull(QueryCapabilityGuard.refusal("BTC", requirements("current", null).put("quote_asset", "USDC"), plan("get_ticker")));
    }
}
