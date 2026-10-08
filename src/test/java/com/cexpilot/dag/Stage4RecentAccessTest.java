package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.funding.FundingQueryService;
import com.cexpilot.market.funding.FundingRecentResult;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 阶段 4a：recent_n 形态的编译准入与端到端取数（对齐回归用例 N13/N17 的形态）。 */
class Stage4RecentAccessTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Instant FIRST = Instant.parse("2026-09-28T00:00:00Z");
    private static final long INTERVAL_MS = 8 * 3_600_000L;
    private final List<DagExecutor> executors = new ArrayList<>();
    @AfterEach void close() { executors.forEach(DagExecutor::shutdown); }

    private static ObjectNode recent(String id, String name, int count, String... exchanges) {
        ObjectNode metric = JSON.createObjectNode().put("id", id).put("metric", name)
                .put("query_shape", "recent_n").put("count", count);
        var ex = metric.putArray("exchanges");
        for (String exchange : exchanges) ex.add(exchange);
        metric.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        return metric;
    }

    private static FundingQueryService funding(int sampleCount) {
        List<FundingRatePoint> points = new ArrayList<>();
        for (int i = 0; i < sampleCount; i++) {
            points.add(new FundingRatePoint(new BigDecimal("0.0001").multiply(BigDecimal.valueOf(i + 1)),
                    FIRST.plusSeconds(i * INTERVAL_MS / 1000).toEpochMilli()));
        }
        var service = mock(FundingQueryService.class);
        when(service.queryRecent(any(Exchange.class), anyString(), anyInt(), any(Instant.class)))
                .thenReturn(new FundingRecentResult(points, sampleCount == 1 ? INTERVAL_MS : null));
        return service;
    }

    private ExecutionResult run(ObjectNode plan, int sampleCount) {
        return run(plan, funding(sampleCount));
    }

    private ExecutionResult run(ObjectNode plan, FundingQueryService service) {
        var llm = new Script(envelope(plan), "回答");
        var config = new DagConfig();
        var providers = new MetricProviderRegistry(List.of(new FundingMetricProvider(service)));
        var executor = new DagExecutor(registry(), config, providers);
        executors.add(executor);
        return new DagRuntime(llm, planner(llm, registry(), config), executor,
                new PromptStore(LOADER), Clock.fixed(NOW, ZoneOffset.UTC))
                .execute("问题", "", "t", e -> {});
    }

    private static com.fasterxml.jackson.databind.JsonNode result(ExecutionResult r, String id) {
        for (var node : r.evidence()) if (id.equals(node.path("node_id").asText())) return node;
        throw new AssertionError("missing " + id);
    }

    @Test void tenPeriodAverageMatchesHandCheckedValue() {
        var plan = plan(recent("m1", "funding.rate_settled", 10, "binance"));
        calculation(plan, "c1", "avg", "{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        var r = run(plan, 10);
        var data = result(r, "metric_0").path("data");
        assertEquals(10, data.path("samples").size());
        assertTrue(data.path("sample_complete").asBoolean());
        assertEquals("ratio", data.path("unit").asText());
        // 0.0001~0.0010 十期等权平均 = 0.00055
        var avg = result(r, "c1").path("data");
        assertEquals(0, new BigDecimal("0.00055").compareTo(new BigDecimal(avg.path("value").asText())));
        assertEquals(10, avg.path("count").asInt());
    }

    @Test void lowestPeriodIsReportedWithItsIndex() {
        var plan = plan(recent("m1", "funding.rate_settled", 10, "binance"));
        calculation(plan, "c1", "min", "{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        var r = run(plan, 10);
        var min = result(r, "c1").path("data");
        assertEquals(0, new BigDecimal("0.0001").compareTo(new BigDecimal(min.path("value").asText())));
        assertEquals(0, min.path("index").asInt());
        assertTrue(min.at("/item/time").isTextual());
    }

    @Test void shortSampleBlocksTheCalculationInsteadOfAveragingIt() {
        var plan = plan(recent("m1", "funding.rate_settled", 10, "binance"));
        calculation(plan, "c1", "avg", "{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        var r = run(plan, 7);
        var data = result(r, "metric_0").path("data");
        assertEquals(7, data.path("actual_count").asInt());
        assertFalse(data.path("sample_complete").asBoolean());
        assertFalse(result(r, "c1").path("ok").asBoolean(), "样本不足时不得静默取均值");
    }

    @Test void singlePeriodIsAnnualizedWithItsOwnSettlementPeriod() {
        var plan = plan(recent("m1", "funding.rate_settled", 1, "binance"));
        calculation(plan, "c1", "annualize",
                "{\"basis\":\"periodic_rate\",\"method\":\"simple\",\"rate\":\"{{m1.binance.samples.0.value}}\","
                        + "\"rate_unit\":\"ratio\",\"period\":{\"value\":\"{{m1.binance.period_seconds}}\",\"unit\":\"second\"}}");
        var r = run(plan, 1);
        var data = result(r, "metric_0").path("data");
        assertEquals(0, new BigDecimal("0.0001").compareTo(data.at("/samples/0/value").decimalValue()));
        assertEquals(0, new BigDecimal("28800.000").compareTo(data.path("period_seconds").decimalValue()));
        var annualized = result(r, "c1").path("data");
        assertEquals(0, new BigDecimal("28800").compareTo(
                new BigDecimal(annualized.path("period_seconds").asText())));
        // 0.0001 × (365×86400/28800) = 0.0001 × 1095 = 0.1095
        assertEquals(0, new BigDecimal("0.1095").compareTo(new BigDecimal(annualized.path("value").asText())));
    }

    @Test void annualizeRejectsMultiPeriodSamplesAndForeignPeriods() {
        // count=10 时 samples.0 是最旧一期，不能当"最近一期"年化
        var multi = plan(recent("m1", "funding.rate_settled", 10, "binance"));
        calculation(multi, "c1", "annualize",
                "{\"basis\":\"periodic_rate\",\"method\":\"simple\",\"rate\":\"{{m1.binance.samples.0.value}}\","
                        + "\"rate_unit\":\"ratio\",\"period\":{\"value\":\"{{m1.binance.period_seconds}}\",\"unit\":\"second\"}}");
        assertFalse(result(run(multi, 10), "c1").path("ok").asBoolean());
        // 用默认 8 小时常量冒充周期同样被拒（该合约周期必须来自本次取数）
        var foreign = plan(recent("m1", "funding.rate_settled", 1, "binance"));
        calculation(foreign, "c1", "annualize",
                "{\"basis\":\"periodic_rate\",\"method\":\"simple\",\"rate\":\"{{m1.binance.samples.0.value}}\","
                        + "\"rate_unit\":\"ratio\",\"period\":{\"value\":28800,\"unit\":\"second\"}}");
        assertFalse(result(run(foreign, 1), "c1").path("ok").asBoolean());
        // 价格涨跌幅更不能冒充费率：basis 不符
        var wrongBasis = plan(recent("m1", "funding.rate_settled", 1, "binance"));
        calculation(wrongBasis, "c1", "annualize",
                "{\"basis\":\"holding_return\",\"method\":\"simple\",\"rate\":\"{{m1.binance.samples.0.value}}\","
                        + "\"rate_unit\":\"ratio\",\"period\":{\"value\":\"{{m1.binance.period_seconds}}\",\"unit\":\"second\"}}");
        assertFalse(result(run(wrongBasis, 1), "c1").path("ok").asBoolean());
    }

    @Test void countIsRequiredAndBoundedByTheCompiler() {
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
        var missing = recent("m1", "funding.rate_settled", 10, "binance");
        missing.remove("count");
        var error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(missing), 8));
        assertTrue(error.getMessage().contains("count"), error.getMessage());
        for (int invalid : new int[]{0, 101}) {
            error = assertThrows(IllegalArgumentException.class,
                    () -> compiler.compile(plan(recent("m1", "funding.rate_settled", invalid, "binance")), 8));
            assertTrue(error.getMessage().contains("count"), error.getMessage());
        }
        // recent_n 不接受时间窗口参数
        var withTime = recent("m1", "funding.rate_settled", 3, "binance");
        withTime.set("time", json(DAY));
        error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(withTime), 8));
        assertTrue(error.getMessage().contains("不接受时间窗口"), error.getMessage());
    }

    @Test void recentNExpandsPerExchange() {
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
        var nodes = compiler.compile(plan(recent("m1", "funding.rate_settled", 5, "binance", "okx")), 8).nodes();
        assertEquals(2, nodes.size());
        for (var node : nodes) {
            var query = assertInstanceOf(CountQuery.class, node.metricQuery());
            assertEquals(5, query.count());
            assertEquals(5, node.args().path("count").asInt());
            assertFalse(node.args().has("time"));
        }
    }

    @Test void secondsCannotBeRelabeledAsHoursAndFailureKeepsOriginalFacts() {
        var plan = plan(recent("m1", "funding.rate_settled", 1, "binance"));
        calculation(plan, "c1", "annualize", """
                {"basis":"periodic_rate","method":"simple","rate":"{{m1.binance.samples.0.value}}",
                 "rate_unit":"ratio","period":{"value":"{{m1.binance.period_seconds}}","unit":"hour"}}
                """);
        var r = run(plan, 1);
        assertTrue(result(r, "metric_0").path("ok").asBoolean());
        assertFalse(result(r, "c1").path("ok").asBoolean());
        assertTrue(result(r, "c1").path("error").asText().contains("second"));
    }

    @Test void unavailableHistoricalCycleBlocksOnlyAnnualization() {
        var service = funding(1);
        when(service.queryRecent(any(Exchange.class), anyString(), anyInt(), any(Instant.class)))
                .thenReturn(new FundingRecentResult(List.of(new FundingRatePoint(new BigDecimal("0.0001"),
                        FIRST.toEpochMilli())), null));
        var plan = plan(recent("m1", "funding.rate_settled", 1, "binance"));
        calculation(plan, "c1", "annualize", """
                {"basis":"periodic_rate","method":"simple","rate":"{{m1.binance.samples.0.value}}",
                 "rate_unit":"ratio","period":{"value":"{{m1.binance.period_seconds}}","unit":"second"}}
                """);
        var r = run(plan, service);
        assertTrue(result(r, "metric_0").path("ok").asBoolean());
        assertTrue(result(r, "metric_0").at("/data/sample_complete").asBoolean());
        assertFalse(result(r, "c1").path("ok").asBoolean());
        assertTrue(result(r, "c1").path("error").asText().contains("缺少可核实"));
    }
    @Test void intermediateAverageCannotBypassSinglePeriodRestriction() {
        var plan = plan(recent("m1", "funding.rate_settled", 10, "binance"));
        calculation(plan, "a", "avg", """
                {"kind":"field","collection":"{{m1.binance.samples}}","field":"value"}
                """);
        calculation(plan, "c1", "annualize", """
                {"basis":"periodic_rate","method":"simple","rate":"{{a.value}}",
                 "rate_unit":"ratio","period":{"value":8,"unit":"hour"}}
                """);
        var r = run(plan, 10);
        assertTrue(result(r, "a").path("ok").asBoolean());
        assertFalse(result(r, "c1").path("ok").asBoolean());
        assertTrue(result(r, "c1").path("error").asText().contains("中间计算结果"));
    }

}
