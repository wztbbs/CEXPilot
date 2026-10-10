package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.markprice.MarkPriceQueryResult;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.market.markprice.PriceType;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.oi.OiQueryRequest;
import com.cexpilot.market.oi.OiQueryResult;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.market.taker.TakerVolumeQueryResult;
import com.cexpilot.market.taker.TakerVolumeQueryService;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.OiInterval;
import com.cexpilot.time.TimeRange;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.mockito.Mockito.*;

/** 阶段 2：oi/mark/index/taker 的编译准入（白名单、固定口径）与端到端取数。 */
class Stage2MetricAccessTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Instant START = Instant.parse("2026-09-28T00:00:00Z");
    private static final TimeRange DAY_RANGE = new TimeRange(START, START.plusSeconds(86400), ZoneOffset.UTC);
    private final List<DagExecutor> executors = new ArrayList<>();
    @AfterEach void close() { executors.forEach(DagExecutor::shutdown); }

    private static ObjectNode withoutInterval(String id, String name, String shape, String... exchanges) {
        ObjectNode metric = metric(id, name, shape, exchanges);
        metric.remove("interval");
        return metric;
    }

    private static MetricPlanCompiler compiler() {
        return new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
    }

    @Test void takerRejectsOkxIntervalAndIncludeUnclosedAtCompileTime() {
        var compiler = compiler();
        var okx = plan(withoutInterval("m1", "taker.buy_volume", "range_statistic", "binance", "okx"));
        var error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(okx, 8));
        assertTrue(error.getMessage().contains("暂不支持交易所 okx"), error.getMessage());
        var withInterval = plan(metric("m1", "taker.buy_volume", "range_statistic", "binance"));
        error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(withInterval, 8));
        assertTrue(error.getMessage().contains("不支持指定粒度"), error.getMessage());
        var unclosed = plan(withoutInterval("m1", "taker.buy_volume", "range_statistic", "binance"));
        ((ObjectNode) unclosed.at("/metrics/0")).put("include_unclosed", true);
        error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(unclosed, 8));
        assertTrue(error.getMessage().contains("include_unclosed"), error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plan(withoutInterval("m1", "taker.buy_volume", "time_series", "binance")), 8));
    }

    @Test void takerCompilesWithoutIntervalAndKeepsRatioUnit() {
        var nodes = compiler().compile(plan(withoutInterval("m1", "taker.buy_ratio", "range_statistic", "binance")), 8).nodes();
        assertEquals(1, nodes.size());
        assertEquals("ratio", nodes.get(0).metric().unit());
        assertEquals(TakerMetric.BUY_RATIO, nodes.get(0).metric().selector());
        var query = assertInstanceOf(TimeRangeQuery.class, nodes.get(0).metricQuery());
        assertNull(query.intervalCode());
        assertFalse(query.includeUnclosed());
    }

    @Test void oiAcceptsWideIntervalsAndExpandsBothExchanges() {
        for (String interval : List.of("30m", "1d")) {
            var group = withoutInterval("m1", "oi.change_pct", "range_statistic", "binance");
            group.put("interval", interval);
            var nodes = compiler().compile(plan(group), 8).nodes();
            assertEquals(interval, assertInstanceOf(TimeRangeQuery.class, nodes.get(0).metricQuery()).intervalCode());
            assertEquals("percent", nodes.get(0).metric().unit());
        }
        var tooWide = withoutInterval("m1", "oi.change_pct", "range_statistic", "binance");
        tooWide.put("interval", "3m");
        var error = assertThrows(IllegalArgumentException.class, () -> compiler().compile(plan(tooWide), 8));
        assertEquals("metrics[0] (m1, oi.change_pct, range_statistic): interval 仅支持 5m/15m/30m/1h/2h/4h/6h/12h/1d", error.getMessage());
        var nodes = compiler().compile(plan(withoutInterval("m1", "oi.end", "range_statistic", "binance", "okx")), 8).nodes();
        assertEquals(2, nodes.size());
        assertEquals(OiMetric.END, nodes.get(0).metric().selector());
    }

    @Test void markAndKlineKeepNarrowIntervals() {
        var group = withoutInterval("m1", "mark.close", "range_statistic", "binance");
        group.put("interval", "30m");
        var error = assertThrows(IllegalArgumentException.class, () -> compiler().compile(plan(group), 8));
        assertEquals("metrics[0] (m1, mark.close, range_statistic): interval 仅支持 5m/15m/1h", error.getMessage());
        group.put("interval", "15m");
        assertEquals(1, compiler().compile(plan(group), 8).nodes().size());
        assertEquals(MarkPriceMetric.MARK_CLOSE, compiler().compile(plan(group), 8).nodes().get(0).metric().selector());
    }

    private static OiQueryService oiService() {
        var points = List.of(new OiPoint(START.toEpochMilli(), new BigDecimal("1000")),
                new OiPoint(START.plusSeconds(82800).toEpochMilli(), new BigDecimal("1100")));
        var request = new OiQueryRequest(Exchange.BINANCE, "BTC", OiInterval.parse("1h"), DAY_RANGE, false);
        var coverage = new SeriesCoverage(2, 2, List.of(), List.of(), List.of(), false, false, null,
                START.plusSeconds(82800).toEpochMilli(), false);
        var service = mock(OiQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString(), any(), anyBoolean()))
                .thenReturn(new OiQueryResult(request, request, points, coverage));
        return service;
    }

    private static TakerVolumeQueryService takerService() {
        var points = List.of(new TakerVolumePoint(START.toEpochMilli(), new BigDecimal("10"), new BigDecimal("5")),
                new TakerVolumePoint(START.plusSeconds(86100).toEpochMilli(), new BigDecimal("20"), new BigDecimal("15")));
        var coverage = new SeriesCoverage(2, 2, List.of(), List.of(), List.of(), false, false, null,
                DAY_RANGE.endExclusive().toEpochMilli(), false);
        var service = mock(TakerVolumeQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString()))
                .thenReturn(new TakerVolumeQueryResult(DAY_RANGE, DAY_RANGE, points, coverage));
        return service;
    }

    private static MarkPriceQueryService markService() {
        var candles = List.of(
                new Candle(START.toEpochMilli(), new BigDecimal("100"), new BigDecimal("105"),
                        new BigDecimal("98"), new BigDecimal("104"), BigDecimal.ZERO, null, true),
                new Candle(START.plusSeconds(3600).toEpochMilli(), new BigDecimal("104"), new BigDecimal("112"),
                        new BigDecimal("103"), new BigDecimal("110"), BigDecimal.ZERO, null, true));
        var coverage = new SeriesCoverage(2, 2, List.of(), List.of(), List.of(), false, false, null,
                DAY_RANGE.endExclusive().toEpochMilli(), false);
        var service = mock(MarkPriceQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(new MarkPriceQueryResult(PriceType.MARK, DAY_RANGE, DAY_RANGE, candles, coverage,
                        CandleInterval.parse("1h")));
        return service;
    }

    private ExecutionResult run(ObjectNode plan, MetricProviderRegistry providers) {
        var llm = new Script(envelope(plan), "回答");
        var config = new DagConfig();
        var executor = new DagExecutor(registry(), config, providers);
        executors.add(executor);
        return new DagRuntime(llm, planner(llm, registry(), config), executor,
                new PromptStore(LOADER), Clock.fixed(NOW, ZoneOffset.UTC))
                .execute("问题", "", "t", e -> {});
    }

    private static JsonNode result(ExecutionResult r, String id) {
        for (JsonNode node : r.evidence()) if (id.equals(node.path("node_id").asText())) return node;
        throw new AssertionError("missing " + id);
    }

    @Test void oiChangePctReachesEvidenceThroughRuntime() {
        var providers = new MetricProviderRegistry(List.of(new OiMetricProvider(oiService(), mock(MarketDataService.class))));
        var r = run(plan(metric("m1", "oi.change_pct", "range_statistic", "binance")), providers);
        var data = result(r, "metric_0").path("data");
        assertEquals(0, new BigDecimal("10.0000").compareTo(data.path("value").decimalValue()));
        assertEquals("percent", data.path("unit").asText());
        assertEquals(new BigDecimal("82800.000"), data.path("observation_seconds").decimalValue());
        assertEquals("oi", data.at("/source/provider").asText());
        assertEquals("CHANGE_PCT", data.at("/source/selector").asText());
        assertEquals("1h", data.path("candle_interval").asText());
    }

    @Test void takerVolumeFeedsCalculationWithBaseUnit() {
        var providers = new MetricProviderRegistry(List.of(new TakerMetricProvider(takerService())));
        var p = plan(withoutInterval("m1", "taker.buy_volume", "range_statistic", "binance"));
        calculation(p, "c1", "difference", "{\"left\":\"{{m1.binance.value}}\",\"right\":10}");
        var r = run(p, providers);
        var data = result(r, "metric_0").path("data");
        assertEquals(0, new BigDecimal("30").compareTo(data.path("value").decimalValue()));
        assertEquals("BTC", data.path("unit").asText());
        assertEquals("5m", data.path("candle_interval").asText());
        var calc = result(r, "c1").path("data");
        assertEquals(0, new BigDecimal("20").compareTo(new BigDecimal(calc.path("value").asText())));
        assertEquals("BTC", calc.path("unit").asText());
        assertEquals(1, calc.path("metric_sources").size());
        assertEquals("taker.buy_volume", calc.at("/metric_sources/0/metric").asText());
    }

    @Test void markCloseSeriesFeedsAverage() {
        var providers = new MetricProviderRegistry(List.of(new MarkPriceMetricProvider(markService(), mock(MarketDataService.class))));
        var p = plan(metric("m1", "mark.close", "time_series", "binance"));
        calculation(p, "a", "avg", "{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        var trace = new ArrayList<TraceEvent>();
        var llm = new Script(envelope(p), "回答");
        var config = new DagConfig();
        var executor = new DagExecutor(registry(), config, providers);
        executors.add(executor);
        var r = new DagRuntime(llm, planner(llm, registry(), config), executor,
                new PromptStore(LOADER), Clock.fixed(NOW, ZoneOffset.UTC))
                .execute("问题", "", "t", trace::add);
        var data = result(r, "metric_0").path("data");
        assertEquals(2, data.path("samples").size());
        assertEquals("mark_price", data.at("/source/provider").asText());
        var avg = result(r, "a");
        assertEquals(0, new BigDecimal("107").compareTo(new BigDecimal(avg.path("data").path("value").asText())));
        assertEquals(1, trace.stream().filter(e -> "METRIC_RESULT".equals(e.eventType())).count());
    }
}
