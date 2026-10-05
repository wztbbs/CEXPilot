package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.metric.MetricCatalog;
import com.cexpilot.metric.MetricPlanCompiler;
import com.cexpilot.runtime.TraceEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 新协议的解析、展开、修复和能力边界；模型为固定脚本，不请求网络。 */
class DagPlannerTest {
    @Test void validPlanExpandsExchangesAndKeepsLogicalAndPhysicalTrace() {
        var p = plan(metric("m1", "trade.turnover", "range_statistic", "binance", "okx"));
        calculation(p, "c1", "relative_change", "{\"current\":\"{{m1.binance.value}}\",\"baseline\":\"{{m1.okx.value}}\"}");
        var llm = new Script(envelope(p));
        List<TraceEvent> events = new ArrayList<>();
        var result = planner(llm, registry(), new DagConfig()).plan("比较成交额", "", "test", events::add);
        assertTrue(result.plan().isPresent(), result.lastError());
        var nodes = result.plan().orElseThrow().nodes();
        assertEquals(3, nodes.size());
        assertEquals(List.of("metric_0", "metric_1"), nodes.get(2).dependsOn());
        assertEquals("{{metric_0.data.value}}", nodes.get(2).args().at("/input/current").asText());
        assertEquals(nodes.get(0).args().get("time"), nodes.get(1).args().get("time"));
        assertEquals("m1", nodes.get(0).metric().groupId());
        assertNull(nodes.get(0).tool());
        assertNotNull(nodes.get(0).metricQuery());
        assertEquals("kline",nodes.get(0).metric().provider());
        assertEquals(com.cexpilot.metric.KlineMetric.TURNOVER,nodes.get(0).metric().selector());
        assertEquals(List.of("LLM_CALL", "PLAN", "PLAN_COMPILED"), events.stream().map(TraceEvent::eventType).toList());
        assertTrue(events.get(2).outputJson().contains("metric_binding"));
    }
    @Test void promptExposesOnlyMetricContractsAndOperators() {
        var llm = new Script(envelope(scalarPlan()));
        planner(llm, registry(), new DagConfig()).plan("昨天成交额", "", "test", e -> {});
        String prompt = llm.calls.get(0).get(0).content();
        for (String expected : List.of("trade.turnover", "price.close", "avg:", "annualize:", "metrics", "samples", "K 线")) assertTrue(prompt.contains(expected), expected);
        for (String old : List.of("get_ticker", "get_market_statistics", "get_klines", "get_funding_rate", "statistics.quote_volume")) assertFalse(prompt.contains(old), old);
    }
    @ParameterizedTest
    @ValueSource(strings={"{}", "[]", "not json", "{\"plan\":{\"metrics\":[],\"calculations\":[]}}", "{\"in_domain\":true,\"reply\":null,\"plan\":null}", "{\"in_domain\":true,\"plan\":{\"nodes\":[]}}"})
    void malformedEnvelopeOrLegacyProtocolRepairs(String bad) {
        var llm = new Script(bad, envelope(scalarPlan()));
        var result = planner(llm, registry(), new DagConfig()).plan("问题", "", "t", e -> {});
        assertTrue(result.plan().isPresent(), result.lastError());
        assertEquals(2, llm.calls.size());
        assertTrue(llm.calls.get(1).get(3).content().contains("错误明细"));
        assertEquals(20, result.promptTokens());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void domainAndClarificationShortCircuit(boolean inDomain) {
        var llm = new Script("{\"in_domain\":"+inDomain+",\"intent\":\"UNKNOWN\",\"plan\":null,\"reply\":\"请明确查询范围\"}");
        var result = planner(llm, registry(), new DagConfig()).plan("问题", "", "t", e -> {});
        assertEquals(inDomain,result.inDomain());
        assertEquals("请明确查询范围",result.reply());
        assertTrue(result.plan().isEmpty());
        assertEquals(1,llm.calls.size());
    }
    @Test void emptyPlanNeedsReplyAndLongReasoningDoesNotLeak() {
        for (boolean empty : List.of(true,false)) {
            ObjectNode e = (ObjectNode) json(envelope(empty ? plan() : scalarPlan()));
            e.put("reply","推理".repeat(60));
            var llm = new Script(e.toString(),envelope(scalarPlan()));
            var result = planner(llm,registry(),new DagConfig()).plan("问题","","t",x -> {});
            assertTrue(result.plan().isPresent());
            assertNull(result.reply());
            assertEquals(empty ? 2 : 1,llm.calls.size());
        }
        ObjectNode e=(ObjectNode)json(envelope(plan())); e.put("reply","请提供币种");
        var llm=new Script(e.toString());
        var result=planner(llm,registry(),new DagConfig()).plan("问题","","t",x -> {});
        assertTrue(result.plan().isEmpty()); assertEquals("请提供币种",result.reply());
        var repair=new Script(envelope(plan()),envelope(scalarPlan()));
        assertTrue(planner(repair,registry(),new DagConfig()).plan("问题","","t",x -> {}).plan().isPresent());
        assertEquals(2,repair.calls.size());
    }
    @Test void unknownIntentAndRetryExhaustion() {
        ObjectNode e=(ObjectNode)json(envelope(scalarPlan()));e.put("intent","INVENTED");
        assertEquals("UNKNOWN",planner(new Script(e.toString()),registry(),new DagConfig()).plan("问题","","t",x -> {}).intent());
        var config=new DagConfig();config.setPlannerMaxRetries(1);
        var llm=new Script("bad","bad");
        var result=planner(llm,registry(),config).plan("问题","","t",x -> {});
        assertTrue(result.plan().isEmpty());assertNotNull(result.lastError());assertEquals(2,llm.calls.size());
    }
    @ParameterizedTest @ValueSource(strings={"json_schema", "\"json_schema\"", "json_object"})
    void responseFormatUsesNewProtocol(String format) {
        var config=new DagConfig();config.setPlannerResponseFormat(format);
        var llm=new Script(envelope(scalarPlan()));
        assertTrue(planner(llm,registry(),config).plan("问题","","t",x -> {}).plan().isPresent());
        if (format.contains("schema")) {
            var properties=llm.format.at("/json_schema/schema/properties/plan/properties");
            assertTrue(properties.has("metrics"));assertTrue(properties.has("calculations"));assertFalse(properties.has("nodes"));
            assertTrue(properties.at("/metrics/items/properties/metric/enum").toString().contains("price.close"));
        } else assertEquals("json_object",llm.format.path("type").asText());
    }
    @Test void unsupportedMetricsMarketsShapesAndUnknownFieldsFailBeforeExecution() {
        var registry=registry();var compiler=new MetricPlanCompiler(new MetricCatalog(LOADER),registry);
        List<ObjectNode> bad=new ArrayList<>();
        var p=scalarPlan();((ObjectNode)p.at("/metrics/0")).put("metric","funding.rate");bad.add(p);
        p=scalarPlan();((ObjectNode)p.at("/metrics/0/instrument")).put("market_type","spot");bad.add(p);
        p=scalarPlan();((ObjectNode)p.at("/metrics/0/instrument")).put("settle","USDC");bad.add(p);
        p=scalarPlan();((ObjectNode)p.at("/metrics/0")).put("query_shape","snapshot");bad.add(p);
        p=scalarPlan();((ObjectNode)p.at("/metrics/0")).put("type","metric");bad.add(p);
        p=scalarPlan();((ObjectNode)p.at("/metrics/0/time")).put("garbage",1);bad.add(p);
        bad.add(plan(metric("m1","trade.turnover","time_series","binance")));
        bad.add(plan(metric("m1","price.close","time_series","binance","binance")));
        for (ObjectNode invalid:bad) assertThrows(IllegalArgumentException.class,()->compiler.compile(invalid,8),invalid::toString);
    }
    @Test void missingInputRepairsAndBudgetCountsExpandedNodes() {
        var valid=scalarPlan();var bad=valid.deepCopy();((ObjectNode)bad.at("/metrics/0")).remove("time");
        var llm=new Script(envelope(bad),envelope(valid));
        assertTrue(planner(llm,registry(),new DagConfig()).plan("问题","","t",x->{}).plan().isPresent());
        assertEquals(2,llm.calls.size());
        var compiler=new MetricPlanCompiler(new MetricCatalog(LOADER),registry());
        assertThrows(IllegalArgumentException.class,()->compiler.compile(plan(metric("m1","price.close","range_statistic","binance","okx")),1));
    }
    @Test void referencesAndCyclesAreValidatedAndForwardReferencesAllowed() {
        for(String reference:List.of("{{m1.value}}","{{m1.kraken.value}}","{{m1.binance.price_usdt}}","{{m1.binance.samples}}","{{missing.value}}","{{m1.binance.value}} + 1")) {
            var p=scalarPlan();calculation(p,"c1","avg","{\"kind\":\"values\",\"values\":[\""+reference+"\",1]}");
            var config=new DagConfig();config.setPlannerMaxRetries(0);
            assertTrue(planner(new Script(envelope(p)),registry(),config).plan("问题","","t",x->{}).plan().isEmpty(),reference);
        }
        var p=scalarPlan();calculation(p,"c2","difference","{\"left\":\"{{c1.value}}\",\"right\":1}");
        calculation(p,"c1","avg","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\",100]}");
        assertTrue(planner(new Script(envelope(p)),registry(),new DagConfig()).plan("问题","","t",x->{}).plan().isPresent());
        ((ObjectNode)p.at("/calculations/1/input")).set("values",json("[\"{{c2.value}}\",100]"));
        var config=new DagConfig();config.setPlannerMaxRetries(0);
        var cycle=planner(new Script(envelope(p)),registry(),config).plan("问题","","t",x->{});
        assertTrue(cycle.plan().isEmpty());assertTrue(cycle.lastError().contains("环"));
    }
}
