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
    @Test void allPromptExamplesParseAndCompileWithoutRepair() throws Exception {
        String prompt = new org.springframework.core.io.ClassPathResource("prompts/dag_planner.txt")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int examples = 0;
        for (String line : prompt.split("\\R")) {
            if (!line.startsWith("{\"in_domain\"") || !line.contains("\"id\"")) continue;
            var envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(line);
            var config = new DagConfig();
            config.setPlannerMaxRetries(0);
            var result = planner(new Script(line), registry(), config).plan("校验示例", "", "test", e -> {});
            assertTrue(result.plan().isPresent(), result.lastError());
            if (envelope.at("/plan/metrics/0/metric").asText().equals("funding.rate_settled")) {
                assertEquals(10, envelope.at("/plan/metrics/0/count").asInt());
                assertFalse(envelope.at("/plan/metrics/0").has("time"));
            }
            if (line.contains("100000")) {
                assertEquals("100000", envelope.at("/plan/calculations/0/input/current").asText());
                assertEquals("{{m1.binance.value}}", envelope.at("/plan/calculations/0/input/baseline").asText());
            }
            examples++;
        }
        assertEquals(6, examples);
    }

    @ParameterizedTest @ValueSource(strings={"", "json_object", "json_schema"})
    void traceRecordsResponseFormatOnInitialAndRepairCalls(String format) {
        var config = new DagConfig();
        config.setPlannerResponseFormat(format);
        var llm = new Script("bad", envelope(scalarPlan()));
        List<TraceEvent> events = new ArrayList<>();
        planner(llm, registry(), config).plan("问题", "", "test", events::add);
        var calls = events.stream().filter(e -> e.eventType().equals("LLM_CALL")).toList();
        assertEquals(2, calls.size());
        for (int i = 0; i < calls.size(); i++) {
            var input = json(calls.get(i).inputJson());
            assertEquals(i + 1, input.path("attempt").asInt());
            assertEquals(i == 0 ? 2 : 4, input.path("messages").size());
            assertEquals(llm.format == null ? com.fasterxml.jackson.databind.node.NullNode.instance : llm.format,
                    input.get("response_format"));
            assertEquals(format.equals("json_schema"), input.has("schema_fingerprint"));
            if (format.equals("json_schema")) {
                assertTrue(input.at("/response_format/json_schema/schema/properties/plan/properties/metrics/items/properties/count").isObject());
            }
        }
    }

    @Test void recentCountMustBeExplicitAndCanRepairWithoutGuessing() {
        var query = metric("m1", "funding.rate_settled", "recent_n", "binance");
        query.remove("interval");
        query.putObject("time");
        var missing = plan(query);
        var valid = missing.deepCopy();
        ((ObjectNode) valid.at("/metrics/0")).put("count", 10).remove("time");
        var llm = new Script(envelope(missing), envelope(valid));
        var result = planner(llm, registry(), new DagConfig()).plan("最近10期", "", "test", e -> {});
        assertTrue(result.plan().isPresent(), result.lastError());
        assertTrue(llm.calls.get(1).get(3).content().contains("count"));
        assertEquals(10, result.plan().orElseThrow().nodes().get(0).args().path("count").asInt());
        var config = new DagConfig(); config.setPlannerMaxRetries(0);
        var rejected = planner(new Script(envelope(missing)), registry(), config).plan("最近10期", "", "test", e -> {});
        assertTrue(rejected.plan().isEmpty());
        assertTrue(rejected.lastError().contains("count"));
    }

    @Test void failedLlmCallStillRecordsRequestParameters() {
        var llm = new Script() {
            @Override public com.cexpilot.llm.ChatResponse chat(List<com.cexpilot.llm.ChatMessage> messages,
                    List<com.cexpilot.llm.ToolSpec> tools, com.fasterxml.jackson.databind.JsonNode format) {
                throw new IllegalStateException("simulated transport failure");
            }
        };
        var config = new DagConfig(); config.setPlannerResponseFormat("json_schema");
        List<TraceEvent> events = new ArrayList<>();
        assertThrows(IllegalStateException.class,
                () -> planner(llm, registry(), config).plan("问题", "", "test", events::add));
        assertEquals(1, events.size());
        assertEquals("simulated transport failure", events.get(0).error());
        assertEquals("json_schema", json(events.get(0).inputJson()).at("/response_format/type").asText());
    }

    @Test void unsupportedFundingExamplesAreValidNoPlanReplies() throws Exception {
        String prompt = new org.springframework.core.io.ClassPathResource("prompts/dag_planner.txt")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int examples = 0;
        for (String line : prompt.split("\\R")) {
            if (!line.startsWith("{\"in_domain\"") || !line.contains("\"plan\":null")) continue;
            var config = new DagConfig(); config.setPlannerMaxRetries(0);
            var result = planner(new Script(line), registry(), config).plan("校验边界示例", "", "test", e -> {});
            assertTrue(result.plan().isEmpty());
            assertNull(result.lastError());
            assertTrue(result.reply().contains("系统尚未接入"));
            examples++;
        }
        assertEquals(2, examples);
    }

    @Test void fundingWindowRepairDoesNotSuggestGuessingCount() {
        var query = metric("m1", "funding.rate_settled", "recent_n", "binance");
        query.remove("interval"); query.put("count", 7);
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
        var error = assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(query), 8));
        assertTrue(error.getMessage().contains("不得把天数或小时数换成 count"));
        query.put("query_shape", "range_statistic"); query.remove("count");
        var unsupported = assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan(query), 8));
        assertTrue(unsupported.getMessage().contains("当前系统未接入"));
        assertTrue(unsupported.getMessage().contains("不代表交易所没有数据"));
    }

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
        for (String old : List.of("get_ticker", "get_market_statistics", "get_klines", "get_funding_rate", "statistics.quote_volume",
                "intent", "MARKET_LOOKUP", "MARKET_ANALYSIS", "EXCHANGE_COMPARE", "意图归类")) assertFalse(prompt.contains(old), old);
    }
    @Test void renderedPromptUsesCatalogCapabilitiesWithoutLegacyBlanketRefusals() {
        var llm = new Script(envelope(scalarPlan()));
        planner(llm, registry(), new DagConfig()).plan("最近10期已结算费率", "", "test", e -> {});
        String prompt = llm.calls.get(0).get(0).content();
        for (String capability : List.of("funding.rate_settled", "recent_n", "price.last", "orderbook.spread",
                "period_seconds", "买卖之比可大于 1")) {
            assertTrue(prompt.contains(capability), capability);
        }
        for (String obsolete : List.of("如现货、资金费率、当前实时报价、盘口", "单期/累计费率尚无取数能力",
                "年化仅支持 price.change_pct 的区间收益率路径", "ratio 是 0~1 的比例", "买方挂单量/卖方挂单量（0~1")) {
            assertFalse(prompt.contains(obsolete), obsolete);
        }
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
        var llm = new Script("{\"in_domain\":"+inDomain+",\"plan\":null,\"reply\":\"请明确查询范围\"}");
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
    @Test void retryExhaustion() {
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
            var schema = llm.format.at("/json_schema/schema");
            assertFalse(schema.path("properties").has("intent"));
            var required = new java.util.HashSet<String>();
            schema.path("required").forEach(field -> required.add(field.asText()));
            assertEquals(java.util.Set.of("in_domain", "plan", "reply"), required);
            var properties=llm.format.at("/json_schema/schema/properties/plan/properties");
            assertTrue(properties.has("metrics"));assertTrue(properties.has("calculations"));assertFalse(properties.has("nodes"));
            assertTrue(properties.at("/metrics/items/properties/metric/enum").toString().contains("price.close"));
        } else assertEquals("json_object",llm.format.path("type").asText());
    }
    @Test void unsupportedMetricsMarketsShapesAndUnknownFieldsFailBeforeExecution() {
        var registry=registry();var compiler=new MetricPlanCompiler(new MetricCatalog(LOADER),registry,providers());
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
        var compiler=new MetricPlanCompiler(new MetricCatalog(LOADER),registry(),providers());
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
