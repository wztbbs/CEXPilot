package com.cexpilot.dag;

import com.cexpilot.calculation.*;
import com.cexpilot.config.*;
import com.cexpilot.metric.*;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.kline.*;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.*;
import java.time.*;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.*;
import java.util.function.BiFunction;

/** 本地假取数与真实 YAML/算子；不访问网络。 */
final class MetricTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    static final String DAY = "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"full\",\"extent\":\"full_period\"}";
    static JsonNode json(String value) {
        try { return JSON.readTree(value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    static ObjectNode metric(String id, String name, String shape, String... exchanges) {
        ObjectNode metric = JSON.createObjectNode().put("id", id).put("metric", name).put("query_shape", shape);
        var ex = metric.putArray("exchanges");
        for (String exchange : exchanges) ex.add(exchange);
        metric.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        metric.set("time", json(DAY));
        metric.put("interval", "1h");
        return metric;
    }
    static ObjectNode plan(ObjectNode... groups) {
        ObjectNode plan = JSON.createObjectNode();
        var metrics = plan.putArray("metrics");
        for (var group : groups) metrics.add(group);
        plan.putArray("calculations");
        return plan;
    }
    static ObjectNode scalarPlan() { return plan(metric("m1", "trade.turnover", "range_statistic", "binance")); }
    static void calculation(ObjectNode plan, String id, String op, String input) {
        ((ArrayNode) plan.get("calculations")).addObject().put("id", id).put("operator", op).set("input", json(input));
    }
    static String envelope(JsonNode plan) {
        return JSON.createObjectNode().put("in_domain", true).putNull("reply").set("plan", plan).toString();
    }
    static AgentTool source(String name, BiFunction<JsonNode, ToolContext, ToolResult> action) {
        return new AgentTool() {
            public String name() { return name; }
            public ToolResult execute(JsonNode args, ToolContext context) { return action.apply(args, context); }
        };
    }
    static ToolRegistry registry(AgentTool... overrides) {
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        for (AgentTool tool : List.of(new AvgTool(), new SumTool(), new MinTool(), new MaxTool(), new CompareTool(),
                new DifferenceTool(), new RatioTool(), new RelativeChangeTool(), new AnnualizeTool())) tools.put(tool.name(), tool);
        for (AgentTool tool : overrides) tools.put(tool.name(), tool);
        var definitions = ToolDefinitionLoader.load(LOADER).stream().filter(d -> tools.containsKey(d.name())).toList();
        return new ToolRegistry(new ArrayList<>(tools.values()), definitions);
    }
    static MetricProvider provider(BiFunction<MetricQuery, RequestContext, MetricResult> action) {
        return namedProvider(KlineMetricProvider.NAME, KlineMetric::valueOf, action);
    }
    static MetricProvider namedProvider(String name, java.util.function.Function<String, MetricSelector> selector,
                                        BiFunction<MetricQuery, RequestContext, MetricResult> action) {
        return new MetricProvider() {
            public String name() { return name; }
            public MetricSelector selector(String selectorName) { return selector.apply(selectorName); }
            public MetricResult query(MetricQuery query, RequestContext context) { return action.apply(query, context); }
        };
    }
    static MetricProviderRegistry providers(MetricProvider provider) { return new MetricProviderRegistry(List.of(provider)); }
    static MetricProviderRegistry providers() { return providers(provider((q,c) -> metricResult(q,c,24,true))); }
    /** 覆盖目录内全部 provider 的编译期桩：selector 可解析，query 不应被编译器调用。 */
    static MetricProviderRegistry catalogProviders() {
        BiFunction<MetricQuery, RequestContext, MetricResult> unused = (q,c) -> {
            throw new UnsupportedOperationException("编译期桩不取数: " + q.binding().metric());
        };
        return new MetricProviderRegistry(List.of(
                provider((q,c) -> metricResult(q,c,24,true)),
                namedProvider(OiMetricProvider.NAME, OiMetric::valueOf, unused),
                namedProvider(MarkPriceMetricProvider.NAME, name -> {
                    try {
                        return MarkPriceMetric.valueOf(name);
                    } catch (IllegalArgumentException e) {
                        return MarkPriceSnapshot.valueOf(name);
                    }
                }, unused),
                namedProvider(TakerMetricProvider.NAME, TakerMetric::valueOf, unused),
                namedProvider(TickerMetricProvider.NAME, TickerMetric::valueOf, unused),
                namedProvider(OrderBookMetricProvider.NAME, OrderBookSnapshot::valueOf, unused),
                namedProvider(FundingMetricProvider.NAME, FundingMetric::valueOf, unused)));
    }
    static MetricResult metricResult(MetricQuery query, RequestContext context, int count, boolean complete) {
        KlineQueryService service = mock(KlineQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString(), any(), anyBoolean()))
                .thenReturn(klineResult(query, context, count, complete));
        return new KlineMetricProvider(service).query(query,context);
    }
    static KlineQueryResult klineResult(MetricQuery query, RequestContext context, int count, boolean complete) {
        var timeQuery = (TimeRangeQuery) query;
        var range = new TimeRangeResolver(Clock.fixed(context.requestTime(),context.userZone()))
                .resolve(context.userZone(),timeQuery.time(),context.requestTime());
        var interval = timeQuery.intervalCode() == null ? CandleInterval.parse("1h") : CandleInterval.parse(timeQuery.intervalCode());
        List<Candle> candles = new ArrayList<>();
        for (int i=0;i<count;i++) candles.add(new Candle(range.startInclusive().toEpochMilli()+i*interval.duration().toMillis(),
                BigDecimal.valueOf(100+i),BigDecimal.valueOf(102+i),BigDecimal.valueOf(99+i),BigDecimal.valueOf(101+i),
                BigDecimal.TEN,BigDecimal.valueOf(i==0 ? (timeQuery.exchange()==Exchange.BINANCE ? 120 : 100) : 0),true));
        var request = new KlineQueryRequest(timeQuery.exchange(),timeQuery.base(),interval,range,timeQuery.includeUnclosed());
        var coverage = new SeriesCoverage(count,count,complete ? List.of() : List.of(range.startInclusive().toEpochMilli()),
                List.of(),List.of(),false,false,null,range.endExclusive().toEpochMilli(),false);
        return new KlineQueryResult(request,request,candles,coverage);
    }
    static DagPlanner planner(LlmClient llm, ToolRegistry registry, DagConfig config) {
        return new DagPlanner(llm, registry, new LlmConfig(), config,
                new PromptStore(LOADER), new PlanValidator(registry, config), catalogProviders());
    }
    static class Script implements LlmClient {
        final Queue<String> outputs = new ArrayDeque<>();
        final List<List<ChatMessage>> calls = new ArrayList<>();
        JsonNode format;
        Script(String... outputs) { this.outputs.addAll(List.of(outputs)); }
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            if (tools != null) throw new AssertionError("Planner/Answer 不应携带 API tools");
            calls.add(List.copyOf(messages));
            return new ChatResponse(outputs.isEmpty() ? "not json" : outputs.remove(), List.of(), 10, 5);
        }
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, JsonNode format) {
            this.format = format;
            return chat(messages, tools);
        }
    }
}
