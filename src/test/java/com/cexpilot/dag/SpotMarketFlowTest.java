package com.cexpilot.dag;

import com.cexpilot.config.*;
import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.evidence.*;
import com.cexpilot.market.*;
import com.cexpilot.market.kline.*;
import com.cexpilot.market.taker.*;
import com.cexpilot.metric.*;
import com.cexpilot.monitor.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.cexpilot.time.TimeRangeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真目录/Planner协议/编译器/Provider/HTTP解析/算子贯通；交易所由本机 HTTP 服务模拟，不请求线上。 */
class SpotMarketFlowTest {
    static final Instant NOW = Instant.parse("2026-10-31T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final RequestContext CTX = new RequestContext(ZoneOffset.UTC, NOW);
    final List<HttpServer> servers = new ArrayList<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final MetricCatalog catalog = new MetricCatalog(LOADER);
    final ToolRegistry tools = registry();
    MetricProviderRegistry providers;
    MetricPlanCompiler compiler;
    DagExecutor executor;

    @BeforeEach void setup() throws Exception {
        var config = new ExchangeConfig();
        config.getBinance().setBaseUrl(server("perpetual"));
        config.getBinance().setSpotBaseUrl(server("spot"));
        config.getOkx().setBaseUrl(server("okx"));
        var binance = new BinanceClient(config);
        var okx = new OkxClient(config);
        var market = new MarketDataService(binance, okx);
        var time = new TimeRangeResolver(CLOCK);
        var klines = new KlineQueryService(List.of(new BinanceKlineSource(binance), new OkxKlineSource(okx, CLOCK)), time);
        var taker = new TakerVolumeQueryService(List.of(new BinanceTakerVolumeSource(binance), new BinanceSpotTakerVolumeSource(binance)), time);
        providers = new MetricProviderRegistry(List.of(new TickerMetricProvider(market), new OrderBookMetricProvider(market),
                new KlineMetricProvider(klines), new TakerMetricProvider(taker)));
        compiler = new MetricPlanCompiler(catalog, tools, providers);
        executor = new DagExecutor(tools, new DagConfig(), providers);
    }
    @AfterEach void close() { if (executor != null) executor.close(); servers.forEach(s -> s.stop(0)); }

    String server(String name) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(name + ":" + path + "?" + query);
            try {
                if (name.equals("okx") && !"CEXPilot/1.0".equals(exchange.getRequestHeaders().getFirst("User-Agent")))
                    throw new IllegalArgumentException("missing UA");
                String body = response(name, path, query).toString();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
                byte[] bytes = e.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start(); servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
    JsonNode response(String name, String path, String query) {
        Map<String, String> q = new HashMap<>();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2); q.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        if (name.equals("okx")) {
            JsonNode result;
            if (path.endsWith("ticker")) result = json("""
                    {"last":"200","open24h":"100","vol24h":"2.5","volCcy24h":"500","ts":"1793433600000"}
                    """);
            else if (path.endsWith("books")) result = json("""
                    {"bids":[["199","2","0","1"]],"asks":[["201","1","0","1"]],"ts":"1793433600000"}
                    """);
            else if (path.endsWith("candles")) {
                long start = Long.parseLong(q.get("before")) + 1;
                long end = Long.parseLong(q.get("after"));
                ArrayNode rows = JSON.createArrayNode();
                for (long t = end - 300000; t >= start; t -= 300000)
                    rows.addArray().add(Long.toString(t)).add("100").add("110").add("90").add("105")
                            .add("2").add("200").add("200").add("1");
                return JSON.createObjectNode().put("code", "0").put("msg", "").set("data", rows);
            } else throw new IllegalArgumentException(path);
            return JSON.createObjectNode().put("code", "0").put("msg", "").set("data", JSON.createArrayNode().add(result));
        }
        if (path.endsWith("24hr")) return json("""
                {"lastPrice":"%s","priceChangePercent":"2","volume":"10","quoteVolume":"2000","closeTime":1793433600000}
                """.formatted(name.equals("spot") ? "201" : "210"));
        if (path.endsWith("depth")) return json("""
                {"lastUpdateId":1,"bids":[["199","2"]],"asks":[["201","1"]]}
                """);
        if (path.endsWith("klines")) {
            long start = Long.parseLong(q.get("startTime")), end = Long.parseLong(q.get("endTime"));
            ArrayNode rows = JSON.createArrayNode();
            for (long t = start; t <= end; t += 300000)
                rows.addArray().add(t).add("100").add("110").add("90").add("105").add("10")
                        .add(t + 299999).add("1000").add(5).add("6").add("600").add("0");
            return rows;
        }
        throw new IllegalArgumentException(path);
    }
    ObjectNode group(String id, String metric, String shape, String market, String... exchanges) {
        var m = metric(id, metric, shape, exchanges);
        ((ObjectNode)m.path("instrument")).put("market_type", market);
        if (shape.equals("snapshot") || shape.equals("official_24h")) { m.remove("time"); m.remove("interval"); }
        else {
            m.set("time", json("{\"type\":\"rolling_window\",\"duration\":{\"value\":1,\"unit\":\"hour\"}}"));
            m.put("interval", "5m");
        }
        return m;
    }
    JsonNode execute(ObjectNode p, String id) {
        var compiled = compiler.compileFull(p, 12);
        var result = executor.execute(compiled.dagPlan(), "spot-test", e -> {}, CTX).context().get(id);
        assertTrue(result.ok(), result.error()); return result.data();
    }
    void number(String expected, JsonNode actual) { assertEquals(0, new java.math.BigDecimal(expected).compareTo(new java.math.BigDecimal(actual.asText())), actual.toString()); }

    @Test void plannerToHttpToCalculationKeepsSpotAndPerpetualSeparate() {
        var p = plan(group("s", "price.last", "snapshot", "spot", "binance", "okx"),
                group("p", "price.last", "snapshot", "perpetual", "binance"));
        calculation(p, "spread", "difference", "{\"left\":\"{{p.binance.value}}\",\"right\":\"{{s.binance.value}}\"}");
        var llm = new Script(envelope(p));
        var config = new DagConfig();
        var planner = new DagPlanner(llm, tools, new LlmConfig(), config, new PromptStore(LOADER),
                new PlanValidator(tools, config), providers);
        var planned = planner.plan("币安BTC永续比现货贵多少，同时列出OKX现货价", "", "t", e -> {});
        assertTrue(planned.plan().isPresent(), planned.lastError());
        var outcome = executor.execute(planned.plan().orElseThrow().dagPlan(), "t", e -> {}, CTX);
        assertTrue(outcome.context().get("spread").ok());
        number("9", outcome.context().get("spread").data().path("value"));
        assertEquals("spot", outcome.context().get("metric_0").data().at("/instrument/market_type").asText());
        assertFalse(outcome.context().get("metric_0").data().path("instrument").has("settle"));
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("spot:/api/v3/ticker/24hr")), requests.toString());
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("perpetual:/fapi/v1/ticker/24hr")), requests.toString());
        assertTrue(requests.stream().anyMatch(x -> x.contains("instId=BTC-USDT") && !x.contains("SWAP")), requests.toString());
    }
    @Test void okxSpotOfficialVolumeAndTurnoverUseDifferentFields() {
        number("2.5", execute(plan(group("m", "trade.volume_24h", "official_24h", "spot", "okx")), "metric_0").path("value"));
        var turnover = execute(plan(group("m", "trade.turnover_24h", "official_24h", "spot", "okx")), "metric_0");
        number("500", turnover.path("value")); assertFalse(turnover.path("estimated").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(group("m", "trade.turnover_24h", "official_24h", "perpetual", "okx")), 8));
    }
    @Test void spotKlineStatisticsAndSeriesUseBaseVolumeInsteadOfQuoteAmount() {
        for (String exchange : List.of("binance", "okx")) {
            number(exchange.equals("okx") ? "24" : "120", execute(plan(group("m", "trade.volume", "range_statistic", "spot", exchange)), "metric_0").path("value"));
            number(exchange.equals("okx") ? "2400" : "12000", execute(plan(group("m", "trade.turnover", "range_statistic", "spot", exchange)), "metric_0").path("value"));
            var p = plan(group("m", "price.close", "time_series", "spot", exchange));
            calculation(p, "avg", "avg", "{\"kind\":\"field\",\"collection\":\"{{m.%s.samples}}\",\"field\":\"value\"}".formatted(exchange));
            number("105", execute(p, "avg").path("value"));
        }
        assertTrue(requests.stream().noneMatch(x -> x.startsWith("perpetual:")), requests.toString());
    }
    @Test void binanceSpotTakerIsDerivedFromKlinesAndOkxCurrencyAggregateIsRejected() {
        for (var entry : Map.of("taker.buy_volume", "72", "taker.sell_volume", "48", "taker.buy_ratio", "0.6").entrySet()) {
            var m = group("m", entry.getKey(), "range_statistic", "spot", "binance"); m.remove("interval");
            number(entry.getValue(), execute(plan(m), "metric_0").path("value"));
        }
        var unsupported = group("m", "taker.buy_volume", "range_statistic", "spot", "okx"); unsupported.remove("interval");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(unsupported), 8));
        assertTrue(requests.stream().allMatch(x -> x.startsWith("spot:/api/v3/klines")), requests.toString());
    }
    @Test void restSpotOrderbookSeparatesCollectionTimeFromExchangeDataTime() {
        var binance = execute(plan(group("m", "orderbook.spread", "snapshot", "spot", "binance")), "metric_0");
        number("2", binance.path("value")); assertFalse(binance.has("as_of"));
        assertTrue(binance.hasNonNull("collected_at")); assertEquals("local_collection", binance.path("timestamp_source").asText());
        var okx = execute(plan(group("m", "orderbook.imbalance", "snapshot", "spot", "okx")), "metric_0");
        number("2", okx.path("value")); assertTrue(okx.hasNonNull("as_of"));
    }
    @ParameterizedTest @ValueSource(strings = {"funding.rate_settled", "oi.quantity", "mark.price", "index.price"})
    void derivativeOnlyMetricsFailBeforeHttp(String name) {
        var m = group("m", name, name.startsWith("funding") ? "recent_n" : "snapshot", "spot", "binance");
        if (name.startsWith("funding")) { m.remove(List.of("time", "interval")); m.put("count", 1); }
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(m), 8));
        assertTrue(requests.isEmpty());
    }
    @Test void spotCannotCarryContractSettlementOrUnsupportedQuote() {
        var m = group("m", "price.last", "snapshot", "spot", "binance");
        ((ObjectNode)m.path("instrument")).put("settle", "USDT");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(m), 8));
        ((ObjectNode)m.path("instrument")).remove("settle"); ((ObjectNode)m.path("instrument")).put("quote", "USDC");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(m), 8)); assertTrue(requests.isEmpty());
    }
    @Test void marketSwitchAppearsInInheritanceDiffAndCannotReusePerpetualFact() {
        var previous = compiler.compileFull(plan(group("m", "price.last", "snapshot", "perpetual", "binance")), 8);
        var next = compiler.compileFull(plan(group("m", "price.last", "snapshot", "spot", "binance")), 8);
        var frame = QueryFrame.fromCompiledPlan("c", "turn", "原问题", NOW, ZoneOffset.UTC, previous, null);
        assertEquals(Set.of("market_type"), new InheritanceValidator().diffKeys(frame, next, CTX));
        assertNull(new InheritanceValidator().check(frame, next, Set.of("market_type"), CTX));
        var store = new EvidenceStore(new InMemoryEvidenceRepository());
        store.saveFact("c", "turn", "t", previous.targets().get(0), JSON.createObjectNode().put("value", 210).put("as_of", "2026-10-31 08:00:00"), NOW);
        var m = group("m", "price.last", "snapshot", "spot", "binance").put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");
        var bound = new EvidenceBinder(store).bind(compiler.compileFull(plan(m), 8), EvidenceAssembler.assignRefs(store.listReusable("c", NOW)), "c", CTX, "t", e -> {});
        assertNotNull(bound.error()); assertTrue(bound.error().contains("instrument 不符"), bound.error());
    }
    @Test void monitorPersistsSpotIdentityAndRejectsMixedMarkets() {
        var m = group("m", "price.change_pct", "range_statistic", "spot", "binance"); m.remove("interval");
        var c = JSON.createObjectNode(); c.set("plan", plan(m));
        c.putObject("monitor").put("action", "create").putArray("conditions").addObject()
                .put("value_ref", "{{m.binance.value}}").put("operator", "GT").put("threshold", 0);
        var dag = new DagConfig();
        var definitions = new MonitorDefinitionCompiler(new MonitorPlanValidator(catalog, tools, providers,
                new PlanValidator(tools, dag), dag, new LlmConfig()), catalog);
        var saved = definitions.compile(c, "UTC");
        assertTrue(saved.at("/rules/0/description").asText().contains("现货"));
        assertEquals("spot", saved.at("/dag/nodes/0/args/market_type").asText());
        var runner = new MonitorPlanRunner(catalog, tools, providers, CLOCK);
        try { assertEquals(MonitorModels.Result.MATCH, runner.run(saved, NOW, NOW).result()); }
        finally { runner.close(); }
        var second = group("p", "price.change_pct", "range_statistic", "perpetual", "binance");
        ((ArrayNode)c.at("/plan/metrics")).add(second);
        ((ArrayNode)c.at("/monitor/conditions")).addObject().put("value_ref", "{{p.binance.value}}").put("operator", "GT").put("threshold", 0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> definitions.compile(c, "UTC")).getMessage().contains("同一市场类型"));
    }
    @Test void monitorSpotOrderbookUsesCollectionTimeAndRejectsStaleCollection() {
        var c = JSON.createObjectNode();
        c.set("plan", plan(group("m", "orderbook.spread", "snapshot", "spot", "binance")));
        c.putObject("monitor").put("action", "create").putArray("conditions").addObject()
                .put("value_ref", "{{m.binance.value}}").put("operator", "GT").put("threshold", 0);
        var dag = new DagConfig();
        var definitions = new MonitorDefinitionCompiler(new MonitorPlanValidator(catalog, tools, providers,
                new PlanValidator(tools, dag), dag, new LlmConfig()), catalog);
        var saved = definitions.compile(c, "UTC");
        for (int age : List.of(0, 301)) {
            var snapshots = new MetricProviderRegistry(List.of(namedProvider("orderbook", OrderBookSnapshot::valueOf,
                    (q,ctx) -> new MetricResult.Point(new MetricResult.CollectedSnapshotMetadata(NOW.minusSeconds(age), ZoneOffset.UTC),
                            java.math.BigDecimal.TEN))));
            var runner = new MonitorPlanRunner(catalog, tools, snapshots, CLOCK);
            try {
                var result = runner.run(saved, NOW, NOW);
                assertEquals(age == 0 ? MonitorModels.Result.MATCH : MonitorModels.Result.UNKNOWN, result.result());
                if (age == 0) {
                    var data = result.evidence().at("/facts/0/data");
                    assertFalse(data.has("as_of")); assertEquals("local_collection", data.path("timestamp_source").asText());
                }
            } finally { runner.close(); }
        }
    }

    @Test void spotHistoricalFactReusesSameMarketAndPreservesItsInstrument() {
        var m = group("m", "price.last", "snapshot", "spot", "binance");
        var compiled = compiler.compileFull(plan(m), 8);
        var payload = execute(plan(m), "metric_0");
        var store = new EvidenceStore(new InMemoryEvidenceRepository());
        store.saveFact("c", "turn", "t", compiled.targets().get(0), payload, NOW);
        m.put("reference_mode", "pinned_result").put("evidence_ref", "fact_1");
        var reused = compiler.compileFull(plan(m), 8);
        var bound = new EvidenceBinder(store).bind(reused, EvidenceAssembler.assignRefs(store.listReusable("c", NOW)),
                "c", CTX, "t", e -> {});
        assertNull(bound.error());
        requests.clear();
        var outcome = executor.execute(reused.dagPlan(), "t", e -> {}, CTX, bound.bindings());
        assertTrue(outcome.context().get("metric_0").ok());
        assertEquals("spot", outcome.context().get("metric_0").data().at("/instrument/market_type").asText());
        assertTrue(requests.isEmpty(), "历史现货快照复用不应重新取数");
    }

}
