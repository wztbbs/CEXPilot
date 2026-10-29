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
            examples++;
            if (line.contains("\"task_action\":\"RESUME\"")) {
                // RESUME 示例依赖任务索引，空索引下被 task_ref 校验打回是正确行为；
                // 其结构与基准切换由专用用例覆盖
                continue;
            }
            var envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(line);
            var config = new DagConfig();
            config.setPlannerMaxRetries(0);
            var result = planner(new Script(line), registry(), config).plan("校验示例", "", "test", e -> {});
            assertTrue(result.plan().isPresent(), result.lastError());
            if (envelope.at("/plan/metrics/0/metric").asText().equals("funding.rate_settled")) {
                assertTrue(envelope.at("/plan/metrics/0").has("count"), "recent_n 示例必须显式 count");
                assertFalse(envelope.at("/plan/metrics/0").has("time"));
            }
            if (line.contains("100000")) {
                assertEquals("100000", envelope.at("/plan/calculations/0/input/current").asText());
                assertEquals("{{m1.binance.value}}", envelope.at("/plan/calculations/0/input/baseline").asText());
            }
        }
        assertEquals(13, examples);
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
        if ("json_schema".equals(format)) {
            var schema = llm.format.at("/json_schema/schema");
            assertTrue(schema.path("required").toString().contains("needs_clarification"));
            assertTrue(schema.path("required").toString().contains("clarify"));
            assertEquals("array", schema.at("/properties/clarify/type").asText());
        }
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
        assertEquals(10, result.plan().orElseThrow().dagPlan().nodes().get(0).args().path("count").asInt());
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
        // 4.3：planner 阶段 LLM 失败抛 PlanningFailure 并携带已发生的调用次数（首调用失败=1）
        var failure = assertThrows(DagPlanner.PlanningFailure.class,
                () -> planner(llm, registry(), config).plan("问题", "", "test", events::add));
        assertEquals(1, failure.llmCalls);
        assertEquals(2, events.size());
        assertEquals("simulated transport failure", events.get(0).error());
        assertEquals("json_schema", json(events.get(0).inputJson()).at("/response_format/type").asText());
    }

    @Test void unsupportedFundingExamplesAreValidNoPlanReplies() throws Exception {
        String prompt = new org.springframework.core.io.ClassPathResource("prompts/dag_planner.txt")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int examples = 0;
        for (String line : prompt.split("\\R")) {
            if (!line.startsWith("{\"in_domain\"") || !line.contains("\"plan\":null")
                    || line.contains("\"needs_clarification\":true")) continue;
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

    @Test void duplicateIdAndMalformedCalculationAreReportedTogether() {
        var bad = scalarPlan();
        calculation(bad, "m1", "avg", "{\"metric\":\"funding.rate_settled\",\"count\":10}");
        var llm = new Script(envelope(bad), envelope(scalarPlan()));
        var result = planner(llm, registry(), new DagConfig()).plan("列出指标", "", "test", e -> {});
        assertTrue(result.plan().isPresent(), result.lastError());
        assertEquals(2, llm.calls.size());
        String repair = llm.calls.get(1).get(3).content();
        assertTrue(repair.contains("calculations[0].id"));
        assertTrue(repair.contains("重复 id"));
        assertTrue(repair.contains("input.kind"));
        assertTrue(repair.contains("输入契约"));
        assertTrue(repair.contains("collection"));
    }

    @Test void generatedSchemaBindsEachOperatorToItsOwnInput() {
        var config = new DagConfig(); config.setPlannerResponseFormat("json_schema");
        var llm = new Script(envelope(scalarPlan()));
        planner(llm, registry(), config).plan("问题", "", "test", e -> {});
        var branches = llm.format.at("/json_schema/schema/properties/plan/properties/calculations/items/anyOf");
        assertEquals(9, branches.size());
        for (var branch : branches) {
            String name = branch.at("/properties/operator/enum/0").asText();
            var tool = (com.cexpilot.calculation.CalculationTool) registry().get(name);
            assertEquals(tool.planningInputSchema(), branch.at("/properties/input"));
            assertFalse(branch.path("additionalProperties").asBoolean(true));
            assertEquals(3, branch.path("required").size());
        }
    }

    @Test void validPlanExpandsExchangesAndKeepsLogicalAndPhysicalTrace() {
        var p = plan(metric("m1", "trade.turnover", "range_statistic", "binance", "okx"));
        calculation(p, "c1", "relative_change", "{\"current\":\"{{m1.binance.value}}\",\"baseline\":\"{{m1.okx.value}}\"}");
        var llm = new Script(envelope(p));
        List<TraceEvent> events = new ArrayList<>();
        var result = planner(llm, registry(), new DagConfig()).plan("比较成交额", "", "test", events::add);
        assertTrue(result.plan().isPresent(), result.lastError());
        var nodes = result.plan().orElseThrow().dagPlan().nodes();
        assertEquals(3, nodes.size());
        assertEquals(List.of("metric_0", "metric_1"), nodes.get(2).dependsOn());
        assertEquals("{{metric_0.data.value}}", nodes.get(2).args().at("/input/current").asText());
        assertEquals(nodes.get(0).args().get("time"), nodes.get(1).args().get("time"));
        assertEquals("m1", nodes.get(0).metric().groupId());
        assertNull(nodes.get(0).tool());
        assertNotNull(nodes.get(0).metricQuery());
        assertEquals("kline",nodes.get(0).metric().provider());
        assertEquals(com.cexpilot.metric.KlineMetric.TURNOVER,nodes.get(0).metric().selector());
        assertEquals(List.of("LLM_CALL", "PLAN", "PLAN_COMPILED", "FOLLOWUP_PLAN", "PLANNER_ATTEMPT"), events.stream().map(TraceEvent::eventType).toList());
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
            assertEquals(java.util.Set.of("in_domain", "plan", "reply", "needs_clarification", "clarify"), required);
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

    /** 与追问计划只差 metric 的活动 frame（其余条件逐项一致），继承对账应只报 [metric]。 */
    private static com.cexpilot.conversation.QueryFrame priceHighFrame() {
        var target = new com.cexpilot.metric.EffectiveTarget("m1", "metric_0", "binance",
                com.cexpilot.metric.ConditionOrigin.EXPLICIT,
                "price.high", "range_statistic",
                json("{\"market_type\":\"perpetual\",\"base\":\"BTC\",\"quote\":\"USDT\"}"),
                com.cexpilot.metric.ConditionOrigin.DEFAULT,
                json(DAY),
                "1h", com.cexpilot.metric.ConditionOrigin.EXPLICIT,
                false, com.cexpilot.metric.ConditionOrigin.DEFAULT,
                null, null, null, "USDT", "kline", "v1");
        return new com.cexpilot.conversation.QueryFrame("f1", "c1", 1, "t1", "昨天币安 BTC 最高价",
                "2026-10-12T00:00:00Z", "Asia/Shanghai", List.of(target), null, null, null);
    }

    private static final com.cexpilot.runtime.RequestContext FOLLOWUP_CTX =
            new com.cexpilot.runtime.RequestContext(java.time.ZoneId.of("Asia/Shanghai"),
                    java.time.Instant.parse("2026-10-12T00:00:00Z"));

    @Test void 继承对账漏报changes先修复教学_模型补声明后放行() {
        var low = plan(metric("m1", "price.low", "range_statistic", "binance"));
        var declared = JSON.createObjectNode();
        declared.put("in_domain", true).putNull("reply");
        declared.set("plan", low);
        declared.putArray("changes").add("metric");
        var llm = new Script(envelope(low), declared.toString());
        List<TraceEvent> events = new ArrayList<>();

        var result = planner(llm, registry(), new DagConfig())
                .plan("最低价呢？", "", "test", events::add, priceHighFrame(), FOLLOWUP_CTX);

        assertTrue(result.plan().isPresent(), result.lastError());
        assertEquals(2, llm.calls.size());
        assertTrue(llm.calls.get(1).get(3).content().contains("changes"), "修复消息要教模型怎么补声明");
        assertTrue(events.stream().noneMatch(e -> "INHERIT_SHADOW".equals(e.eventType())));
    }

    @Test void 漏报changes修复仍不声明时第二次shadow放行并记事件() {
        var undeclared = envelope(plan(metric("m1", "price.low", "range_statistic", "binance")));
        var llm = new Script(undeclared, undeclared); // 模型两次都不声明（线上实测 qwen-plus 行为）
        List<TraceEvent> events = new ArrayList<>();

        var result = planner(llm, registry(), new DagConfig())
                .plan("最低价呢？", "", "test", events::add, priceHighFrame(), FOLLOWUP_CTX);

        assertTrue(result.plan().isPresent(), "计划本身合法，不得因漏报审计元数据而拒答: " + result.lastError());
        assertEquals(2, llm.calls.size());
        var shadow = events.stream().filter(e -> "INHERIT_SHADOW".equals(e.eventType())).toList();
        assertEquals(1, shadow.size());
        assertTrue(shadow.get(0).inputJson().contains("metric"));
    }

    @Test void 已填写的无效值仍可纠错且不按key过滤() {
        var state = com.cexpilot.conversation.TaskState.legacy(priceHighFrame(), 1, "比较平均费率")
                .withClarificationAnswers(List.of(new com.cexpilot.conversation.ClarificationAnswer("count", "期数", "200"),
                        new com.cexpilot.conversation.ClarificationAnswer("base", "币种", "BTC和ETH")));
        var output = """
                {"in_domain":true,"needs_clarification":true,"plan":null,"clarify":[
                 {"key":"count","label":"期数","type":"number","reason":"invalid","message":"最多100期，您填写了200，请修改","min":1,"max":100},
                 {"key":"base","label":"币种","type":"select","reason":"confirm","message":"本次比较BTC还是ETH？","options":["BTC","ETH"]}]}
                """;
        var llm = new Script(output);
        var result = planner(llm, registry(), new DagConfig()).plan("补充查询条件", "", "t", e -> {},
                priceHighFrame(), FOLLOWUP_CTX, null, List.of(), state, true);
        assertTrue(result.needsClarification());
        assertEquals(2, result.clarificationFields().size(), "同key纠错和确认项均必须保留");
        assertEquals("200", result.clarificationFields().get(0).value().asText());
        assertEquals("BTC和ETH", result.clarificationFields().get(1).value().asText());
        assertTrue(result.reply().contains("最多100期"));
        assertEquals(1, llm.calls.size());
    }

    @Test void 纠错没有原因时专项修复原因后仍展示同一字段() {
        var state = com.cexpilot.conversation.TaskState.legacy(priceHighFrame(), 1, "比较平均费率")
                .withClarificationAnswers(List.of(new com.cexpilot.conversation.ClarificationAnswer("count", "期数", "200")));
        var llm = new Script("{\"in_domain\":true,\"needs_clarification\":true,\"plan\":null,\"clarify\":[{\"key\":\"count\",\"label\":\"期数\",\"type\":\"number\",\"reason\":\"invalid\"}]}",
                "{\"clarify\":[{\"key\":\"count\",\"label\":\"期数\",\"type\":\"number\",\"reason\":\"invalid\",\"message\":\"期数最多100，请修改200\"}]}");
        var result = planner(llm, registry(), new DagConfig()).plan("200", "", "t", e -> {},
                priceHighFrame(), FOLLOWUP_CTX, null, List.of(), state, true);
        assertEquals("200", result.clarificationFields().get(0).value().asText());
        assertEquals("invalid", result.clarificationFields().get(0).reason());
        assertEquals(2, llm.calls.size());
    }

    @Test void 选择框必须有选项的约束同时下发给首轮和修复轮() {
        var invalid = "{\"in_domain\":true,\"needs_clarification\":true,\"plan\":null,\"clarify\":[{\"key\":\"basis\",\"label\":\"比较方式\",\"type\":\"select\"}]}";
        var valid = "{\"clarify\":[{\"key\":\"comparison\",\"label\":\"比较方式\",\"type\":\"select\",\"options\":[\"最近一期已结算费率\",\"最近几期已结算费率的平均值\"]}]}";
        var llm = new Script(invalid, valid);
        var config = new DagConfig(); config.setPlannerResponseFormat("json_schema");
        var events = new ArrayList<TraceEvent>();
        var result = planner(llm, registry(), config).plan("两所Funding谁更高", "", "t", events::add);
        assertTrue(result.needsClarification());
        assertEquals(2, result.clarificationFields().get(0).options().size());
        for (var event : events.stream().filter(e -> "LLM_CALL".equals(e.eventType())).toList()) {
            var branches = json(event.inputJson()).at("/response_format/json_schema/schema/properties/clarify/items/anyOf");
            assertEquals(4, branches.size());
            for (var branch : branches) {
                assertTrue(branch.path("required").toString().contains("reason"));
                assertTrue(branch.path("required").toString().contains("message"));
            }
            var multi = branches.get(3);
            assertEquals("multiselect", multi.at("/properties/type/enum/0").asText());
            assertTrue(multi.path("required").toString().contains("options"));
            assertTrue(multi.path("required").toString().contains("minSelections"));
            assertTrue(multi.path("required").toString().contains("maxSelections"));
            assertTrue(multi.path("properties").has("minSelections"));
            var select = branches.get(2);
            assertTrue(select.path("required").toString().contains("options"));
            assertEquals(1, select.at("/properties/options/minItems").asInt());
            assertEquals("array", select.at("/properties/options/type").asText());
        }
    }

    @Test void 用户日志中的缺选项和重复字段导致明确失败而不是缓存问题() {
        var initial = "{\"changes\":[],\"clarify\":[{\"key\":\"base\",\"label\":\"币种\",\"type\":\"text\"},{\"key\":\"basis\",\"label\":\"比较方式\",\"type\":\"select\"}],\"in_domain\":true,\"needs_clarification\":true,\"plan\":{\"calculations\":[],\"metrics\":[]},\"reply\":null,\"task_action\":\"NEW\"}";
        var repair = "{\"clarify\":[{\"key\":\"base\",\"label\":\"币种\",\"type\":\"text\"},{\"key\":\"comparison\",\"label\":\"比较方式\",\"type\":\"select\"},{\"key\":\"comparison\",\"label\":\"比较方式\",\"type\":\"select\"}]}";
        var llm = new Script(initial, repair);
        var result = planner(llm, registry(), new DagConfig()).plan("两所Funding谁更高", "", "t", e -> {});
        assertFalse(result.needsClarification());
        assertTrue(result.clarificationFields().isEmpty());
        assertNotNull(result.lastError());
        assertTrue(result.reply().contains("澄清表单生成失败"));
        assertEquals(2, llm.calls.size());
    }

    @Test void 空澄清列表定向修复后返回表单且保留上下文() {
        String empty = "{\"clarify\":[],\"in_domain\":true,\"needs_clarification\":true,\"plan\":{\"calculations\":[],\"metrics\":[]},\"reply\":null,\"task_action\":\"CONTINUE\",\"task_ref\":null}";
        var llm = new Script(empty, "{\"clarify\":[{\"key\":\"base\",\"label\":\"币种\",\"type\":\"text\"}]}");
        var config = new DagConfig(); config.setPlannerResponseFormat("json_schema");
        var events = new ArrayList<TraceEvent>();
        var result = planner(llm, registry(), config).plan("两所 Funding 谁更高？", "已给出币安和OKX、永续", "t", events::add);
        assertTrue(result.needsClarification());
        assertEquals(1, result.clarificationFields().size());
        assertEquals(2, llm.calls.size());
        assertEquals(2, llm.calls.get(1).size(), "专项修复不重复整个planner消息链");
        assertTrue(llm.calls.get(1).get(0).content().contains("只负责修复"));
        assertTrue(llm.calls.get(1).get(1).content().contains("已给出币安和OKX、永续"));
        assertTrue(llm.calls.get(1).get(1).content().contains("两所 Funding 谁更高"));
        assertEquals(1, llm.format.at("/json_schema/schema/properties/clarify/minItems").asInt());
        assertEquals(1, llm.format.at("/json_schema/schema/properties").size(), "修复schema不再包含查询和算子");
        assertTrue(events.stream().anyMatch(e -> "CLARIFY_INVALID".equals(e.eventType())));
        assertEquals("REPAIR_REQUIRED", json(events.stream().filter(e -> "PLANNER_ATTEMPT".equals(e.eventType())).findFirst().orElseThrow().outputJson()).path("result").asText());
    }

    @ParameterizedTest @ValueSource(strings={"{\"clarify\":[]}", "bad", "{\"plan\":{\"metrics\":[]},\"reply\":\"换个查询\"}"})
    void 澄清修复仍无字段时失败且只修一次(String repair) {
        var llm = new Script("{\"in_domain\":true,\"needs_clarification\":true,\"clarify\":[],\"plan\":null}", repair);
        var config = new DagConfig(); config.setPlannerMaxRetries(5);
        var events = new ArrayList<TraceEvent>();
        var result = planner(llm, registry(), config).plan("两所Funding谁更高", "", "t", events::add);
        assertFalse(result.needsClarification(), "无字段不能伪装成澄清成功");
        assertTrue(result.plan().isEmpty());
        assertNotNull(result.lastError());
        assertEquals(2, result.llmCalls());
        assertEquals(2, llm.calls.size());
        var attempts = events.stream().filter(e -> "PLANNER_ATTEMPT".equals(e.eventType())).toList();
        assertEquals("REPAIR_EXHAUSTED", json(attempts.get(1).outputJson()).path("result").asText());
    }

    @Test void 禁止重试时空澄清列表也不能标成功() {
        var llm = new Script("{\"in_domain\":true,\"needs_clarification\":true,\"clarify\":[],\"plan\":null}");
        var config = new DagConfig(); config.setPlannerMaxRetries(0);
        var result = planner(llm, registry(), config).plan("两所Funding谁更高", "", "t", e -> {});
        assertFalse(result.needsClarification());
        assertNotNull(result.lastError());
        assertEquals(1, llm.calls.size());
    }

    @Test void 专项修复附带计划不能绕过澄清执行且保留选中任务() {
        var env = JSON.createObjectNode(); env.set("plan", scalarPlan());
        env.put("task_action", "NEW");
        env.putArray("clarify").addObject().put("key", "count").put("label", "期数").put("type", "number");
        var llm = new Script("{\"in_domain\":true,\"needs_clarification\":true,\"clarify\":[],\"plan\":null}", env.toString());
        var frame = priceHighFrame();
        var result = planner(llm, registry(), new DagConfig()).plan("它的均值呢？", "", "t", e -> {}, frame, FOLLOWUP_CTX);
        assertTrue(result.needsClarification());
        assertTrue(result.plan().isEmpty());
        assertEquals(frame.frameId(), result.taskScope().frame().frameId());
        assertEquals(2, llm.calls.size());
    }

    @Test void 结构化澄清列表只调用一次且绝不执行附带计划() {
        var env = JSON.createObjectNode().put("in_domain", true).put("needs_clarification", true);
        env.set("plan", scalarPlan());
        env.putArray("clarify").addObject().put("key", "base").put("label", "币种")
                .put("type", "text").put("placeholder", "例如 BTC、ETH");
        var llm = new Script(env.toString());
        var events = new ArrayList<TraceEvent>();
        var result = planner(llm, registry(), new DagConfig()).plan("两所Funding谁更高", "", "t", events::add);
        assertTrue(result.needsClarification());
        assertTrue(result.plan().isEmpty());
        assertEquals("base", result.clarificationFields().get(0).key());
        assertTrue(result.reply().contains("币种"));
        assertEquals(1, result.llmCalls());
        assertEquals(1, llm.calls.size());
        assertTrue(events.stream().anyMatch(e -> "PLAN_DROPPED".equals(e.eventType())));
    }

    @Test void 有澄清列表但漏旗也立即交给用户不重试() {
        var llm = new Script("{\"in_domain\":true,\"plan\":null,\"clarify\":[{\"key\":\"base\",\"label\":\"币种\",\"type\":\"text\"}]}");
        var result = planner(llm, registry(), new DagConfig()).plan("两所Funding谁更高", "", "t", e -> {});
        assertTrue(result.needsClarification());
        assertEquals(1, llm.calls.size());
        assertEquals(1, result.clarificationFields().size());
    }

    @Test void 非法表单不会部分展示而是定向修复整组() {
        var llm = new Script("{\"in_domain\":true,\"needs_clarification\":true,\"plan\":null,\"clarify\":[{\"key\":\"base\",\"label\":\"币种\",\"type\":\"text\"},{\"key\":\"base\",\"label\":\"期数\",\"type\":\"number\"}]}",
                "{\"clarify\":[{\"key\":\"base\",\"label\":\"币种\",\"type\":\"text\"},{\"key\":\"count\",\"label\":\"期数\",\"type\":\"number\"}]}");
        var result = planner(llm, registry(), new DagConfig()).plan("两所Funding谁更高", "", "t", e -> {});
        assertTrue(result.needsClarification());
        assertEquals(2, result.clarificationFields().size());
        assertEquals(2, llm.calls.size());
    }

    @Test void 空计划首次修复可转为结构化澄清且不再生成回答() {
        var llm = new Script("{\"in_domain\":true,\"plan\":{\"metrics\":[],\"calculations\":[]},\"reply\":null}",
                "{\"in_domain\":true,\"needs_clarification\":true,\"plan\":null,\"clarify\":[{\"key\":\"base\",\"label\":\"币种\",\"type\":\"text\"}]}");
        var result = planner(llm, registry(), new DagConfig()).plan("两所Funding谁更高", "", "t", e -> {});
        assertTrue(result.needsClarification());
        assertEquals(2, llm.calls.size());
        assertEquals(1, result.clarificationFields().size());
    }

    // ===== 阶段二：reference_mode / evidence_ref 协议与绑定 =====

    private static ObjectNode pinnedSnapshotGroup(String ref) {
        ObjectNode group = JSON.createObjectNode().put("id", "m1").put("metric", "price.last")
                .put("query_shape", "snapshot");
        group.putArray("exchanges").add("binance");
        group.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        if (ref != null) {
            group.put("reference_mode", "pinned_result").put("evidence_ref", ref);
        }
        return group;
    }

    /** 在库存入一条 price.last 快照事实，返回绑定上下文（候选 ref=fact_1）。 */
    private com.cexpilot.evidence.EvidenceBindingContext seedContext(com.cexpilot.evidence.EvidenceStore store,
                                                                     String conversationId) throws Exception {
        var compiler = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders());
        var compiled = compiler.compileFull(plan(pinnedSnapshotGroup(null)), 8);
        var target = compiled.targets().get(0);
        ObjectNode payload = JSON.createObjectNode()
                .put("metric", "price.last").put("exchange", "binance").put("query_shape", "snapshot")
                .put("unit", "USDT").put("catalog_version", target.catalogVersion())
                .put("as_of", "2026-10-12 09:00:00").put("value", 83000);
        payload.set("instrument", target.instrument());
        payload.putObject("source").put("provider", target.provider()).put("selector", "LAST");
        store.saveFact(conversationId, "turn-1", "trace-1", target, payload,
                java.time.Instant.parse("2026-10-12T01:00:00Z"));
        return new com.cexpilot.evidence.EvidenceBindingContext(conversationId,
                com.cexpilot.evidence.EvidenceAssembler.assignRefs(
                        store.listReusable(conversationId, java.time.Instant.parse("2026-10-12T01:30:00Z"))),
                store);
    }

    @Test void pinned计划通过服务端绑定并随PlanOutcome返回() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidenceCtx = seedContext(store, "conv-1");
        var llm = new Script(envelope(plan(pinnedSnapshotGroup("fact_1"))));
        List<TraceEvent> events = new ArrayList<>();

        var result = planner(llm, registry(), new DagConfig())
                .plan("刚才那个价格再确认下", "", "t", events::add, null, FOLLOWUP_CTX, evidenceCtx);

        assertTrue(result.plan().isPresent(), result.lastError());
        assertNotNull(result.bindings());
        assertNotNull(result.bindings().reuseFor("metric_0"));
        assertEquals(83000, result.bindings().reuseFor("metric_0").payload().path("value").asDouble());
        assertTrue(events.stream().anyMatch(e -> e.eventType().equals("EVIDENCE_REUSE_DECISION")
                && e.inputJson().contains("\"decision\":\"REUSE\"")));
        assertEquals(1, llm.calls.size());
    }

    @Test void 非法evidence_ref进修复_修复后放行() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidenceCtx = seedContext(store, "conv-1");
        var llm = new Script(envelope(plan(pinnedSnapshotGroup("fact_9"))),
                envelope(plan(pinnedSnapshotGroup("fact_1"))));

        var result = planner(llm, registry(), new DagConfig())
                .plan("刚才那个价格", "", "t", e -> {}, null, FOLLOWUP_CTX, evidenceCtx);

        assertTrue(result.plan().isPresent(), result.lastError());
        assertEquals(2, llm.calls.size());
        String repair = llm.calls.get(1).get(3).content();
        assertTrue(repair.contains("evidence_ref"), repair);
        assertTrue(repair.contains("fact_1"), repair);
    }

    @Test void 绑定修复一次仍失败则澄清原快照不可恢复() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidenceCtx = seedContext(store, "conv-1");
        var bad = envelope(plan(pinnedSnapshotGroup("fact_9")));
        var llm = new Script(bad, bad, bad);
        List<TraceEvent> events = new ArrayList<>();

        var result = planner(llm, registry(), new DagConfig())
                .plan("刚才那个价格", "", "t", events::add, null, FOLLOWUP_CTX, evidenceCtx);

        assertTrue(result.needsClarification());
        assertTrue(result.reply().contains("不可恢复"), result.reply());
        assertTrue(result.plan().isEmpty(), "绑定失败不得带计划执行，禁止重查冒充");
        assertEquals(2, llm.calls.size(), "绑定失败只修复一次");
    }

    @Test void 绑定失败后修复轮删除pinned引用时拦截降级并澄清() throws Exception {
        // R4：第一次 ref 选错进修复；第二次模型把 m1 从 pinned 降级成 latest 绕过——
        // 程序必须识破，按"原快照不可恢复"澄清，不得静默执行偷换成现价
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidenceCtx = seedContext(store, "conv-1");
        var downgraded = envelope(plan(pinnedSnapshotGroup(null))); // 同组但无 pinned 字段 = latest
        var llm = new Script(envelope(plan(pinnedSnapshotGroup("fact_9"))), downgraded);
        List<TraceEvent> events = new ArrayList<>();

        var result = planner(llm, registry(), new DagConfig())
                .plan("现在比刚才涨了多少？", "", "t", events::add, null, FOLLOWUP_CTX, evidenceCtx);

        assertTrue(result.needsClarification(), "降级 latest 不得被接受: " + result.lastError());
        assertTrue(result.reply().contains("不可恢复"), result.reply());
        assertTrue(result.plan().isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.outputJson() != null
                && e.outputJson().contains("pinned_downgrade_blocked")), "降级拦截要落审计事件");
        assertEquals(2, llm.calls.size());
    }

    @Test void 绑定失败后修复轮修正ref为pinned时正常放行() throws Exception {
        // R4 不误伤：修复轮保持 pinned、只修正 ref → 正常接受（已有用例覆盖，此处补降级判定的对照）
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidenceCtx = seedContext(store, "conv-1");
        var llm = new Script(envelope(plan(pinnedSnapshotGroup("fact_9"))),
                envelope(plan(pinnedSnapshotGroup("fact_1"))));

        var result = planner(llm, registry(), new DagConfig())
                .plan("现在比刚才涨了多少？", "", "t", e -> {}, null, FOLLOWUP_CTX, evidenceCtx);

        assertTrue(result.plan().isPresent(), result.lastError());
        assertFalse(result.needsClarification());
    }

    @Test void 提示词反向比较示例保持旧值在左并可执行() throws Exception {
        String prompt = new org.springframework.core.io.ClassPathResource("prompts/dag_planner.txt")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(prompt.contains("relative_change 方向固定"));
        assertFalse(prompt.contains("方向接反或字面量冒充都会被服务端打回"));
        int exampleStart = prompt.indexOf("示例：【可引用历史事实】中有 ref=fact_1（币安 BTC 的旧价快照）");
        assertTrue(exampleStart >= 0);
        String example = prompt.substring(exampleStart).lines().filter(line -> line.startsWith("{\"in_domain\""))
                .findFirst().orElseThrow();
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var result = planner(new Script(example), registry(), new DagConfig()).plan("用刚才的价格减去现在的价格", "", "t",
                e -> {}, null, FOLLOWUP_CTX, seedContext(store, "conv-1"));
        assertTrue(result.plan().isPresent(), result.lastError());
        var calc = result.plan().orElseThrow().logicalPlan().calculations().get(0);
        assertEquals("difference", calc.operator());
        assertEquals("{{m1.binance.value}}", calc.input().path("left").asText());
        assertEquals("{{m2.binance.value}}", calc.input().path("right").asText());
        assertNotNull(result.bindings().reuseFor("metric_0"));
        assertNull(result.bindings().reuseFor("metric_1"));
    }

    @Test void 多历史输入不能只保留其中一个pinned() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidence = seedContext(store, "conv-1");
        var first = pinnedSnapshotGroup("fact_1");
        var second = pinnedSnapshotGroup("fact_9"); second.put("id", "m2");
        var bad = plan(first, second);
        calculation(bad, "c1", "difference", "{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m2.binance.value}}\"}");
        var repaired = bad.deepCopy();
        ((ObjectNode) repaired.at("/metrics/1")).remove(List.of("reference_mode", "evidence_ref"));
        var result = planner(new Script(envelope(bad), envelope(repaired)), registry(), new DagConfig())
                .plan("刚才两次价格的差值", "", "t", e -> {}, null, FOLLOWUP_CTX, evidence);
        assertTrue(result.needsClarification());
        assertTrue(result.plan().isEmpty(), "一个 pinned 不能替代另一个历史输入");
    }

    @Test void 修复改名和重排节点仍可保留全部历史输入() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidence = seedContext(store, "conv-1");
        var old1 = pinnedSnapshotGroup("fact_1");
        var old2 = pinnedSnapshotGroup("fact_9"); old2.put("id", "m2");
        var bad = plan(old1, old2);
        calculation(bad, "c1", "difference", "{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m2.binance.value}}\"}");
        var renamed1 = pinnedSnapshotGroup("fact_1"); renamed1.put("id", "old_left");
        var renamed2 = pinnedSnapshotGroup("fact_1"); renamed2.put("id", "old_right");
        var repaired = plan(renamed2, renamed1);
        calculation(repaired, "delta", "difference", "{\"left\":\"{{old_left.binance.value}}\",\"right\":\"{{old_right.binance.value}}\"}");
        var result = planner(new Script(envelope(bad), envelope(repaired)), registry(), new DagConfig())
                .plan("刚才两次价格的差值", "", "t", e -> {}, null, FOLLOWUP_CTX, evidence);
        assertTrue(result.plan().isPresent(), result.lastError());
        assertFalse(result.needsClarification(), "节点 ID 与顺序不构成历史身份");
        assertEquals(2, result.bindings().reusedByNode().size());
    }

    @Test void 修复不能把历史基准转移成无关pinned节点() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidence = seedContext(store, "conv-1");
        var old = pinnedSnapshotGroup("fact_9");
        var now = pinnedSnapshotGroup(null); now.put("id", "m2");
        var bad = plan(old, now);
        calculation(bad, "c1", "relative_change", "{\"current\":\"{{m2.binance.value}}\",\"baseline\":\"{{m1.binance.value}}\"}");
        var dummy = pinnedSnapshotGroup("fact_1"); dummy.put("id", "kept_old");
        var fresh1 = pinnedSnapshotGroup(null); fresh1.put("id", "fresh1");
        var fresh2 = pinnedSnapshotGroup(null); fresh2.put("id", "fresh2");
        var repaired = plan(dummy, fresh1, fresh2);
        calculation(repaired, "delta", "relative_change", "{\"current\":\"{{fresh1.binance.value}}\",\"baseline\":\"{{fresh2.binance.value}}\"}");
        var result = planner(new Script(envelope(bad), envelope(repaired)), registry(), new DagConfig())
                .plan("比刚才涨了多少", "", "t", e -> {}, null, FOLLOWUP_CTX, evidence);
        assertTrue(result.needsClarification());
        assertTrue(result.plan().isEmpty(), "保留无关历史节点不能让比较基准变成最新值");
    }

    @Test void 改名但保持多层计算历史输入时允许修复() throws Exception {
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var evidence = seedContext(store, "conv-1");
        var bad = plan(pinnedSnapshotGroup("fact_9"));
        calculation(bad, "c1", "difference", "{\"left\":\"{{m1.binance.value}}\",\"right\":100}");
        calculation(bad, "c2", "ratio", "{\"left\":\"{{c1.value}}\",\"right\":2}");
        var renamed = pinnedSnapshotGroup("fact_1"); renamed.put("id", "old");
        var repaired = plan(renamed);
        calculation(repaired, "half", "ratio", "{\"left\":\"{{delta.value}}\",\"right\":2}");
        calculation(repaired, "delta", "difference", "{\"left\":\"{{old.binance.value}}\",\"right\":100}");
        var result = planner(new Script(envelope(bad), envelope(repaired)), registry(), new DagConfig())
                .plan("原价格减100再除以2", "", "t", e -> {}, null, FOLLOWUP_CTX, evidence);
        assertTrue(result.plan().isPresent(), result.lastError());
        assertFalse(result.needsClarification());
    }

    @Test void 无证据上下文时pinned字段不触发绑定() {
        // 单轮/老链路：没有证据上下文，pinned 字段只作协议解析，不做绑定（保持旧行为兼容）
        var llm = new Script(envelope(plan(pinnedSnapshotGroup("fact_1"))));
        var result = planner(llm, registry(), new DagConfig()).plan("问题", "", "t", e -> {});
        assertTrue(result.plan().isPresent(), result.lastError());
        assertNull(result.bindings());
    }

    @Test void plannerLlmCalls计数含修复重试() {
        var query = metric("m1", "funding.rate_settled", "recent_n", "binance");
        query.remove("interval");
        query.putObject("time");
        var missing = plan(query);
        var valid = missing.deepCopy();
        ((ObjectNode) valid.at("/metrics/0")).put("count", 10).remove("time");
        // 首轮缺 count 被打回，第二轮修复成功：llmCalls 应为 2
        var repaired = planner(new Script(envelope(missing), envelope(valid)), registry(), new DagConfig())
                .plan("最近10期", "", "test", e -> {});
        assertTrue(repaired.plan().isPresent());
        assertEquals(2, repaired.llmCalls());
        // 一次通过：llmCalls 为 1
        var clean = planner(new Script(envelope(valid)), registry(), new DagConfig())
                .plan("最近10期", "", "test", e -> {});
        assertEquals(1, clean.llmCalls());
    }

    @Test void followupPlan事件记录口径绑定决策与声明() {
        var llm = new Script(envelope(scalarPlan()));
        List<TraceEvent> events = new ArrayList<>();
        planner(llm, registry(), new DagConfig()).plan("昨天成交额", "", "test", events::add);

        var summary = events.stream().filter(e -> "FOLLOWUP_PLAN".equals(e.eventType())).findFirst().orElseThrow();
        var out = json(summary.outputJson());
        assertEquals("trade.turnover", out.at("/metrics/0/metric").asText());
        assertEquals("latest", out.at("/metrics/0/reference_mode").asText(), "无证据上下文时为 latest");
        assertEquals("binance", out.at("/metrics/0/branches/0/exchange").asText());
        assertEquals("FETCH", out.at("/metrics/0/branches/0/decision").asText(), "未绑定时决策为 FETCH");
        assertEquals(0, out.at("/calculations").size());
        assertEquals(0, out.path("changes").size(), "单轮无修改声明");
        assertFalse(out.path("new_frame").asBoolean());
    }

    @Test void 出域或澄清附带plan时服务端丢弃并落审计事件() {
        // 终态分支附带真内容 plan 属协议违规：plan 不可执行，丢弃并落 PLAN_DROPPED
        ObjectNode outEnv = JSON.createObjectNode();
        outEnv.put("in_domain", false).put("reply", "超出范围");
        outEnv.set("plan", scalarPlan());
        List<TraceEvent> events = new ArrayList<>();
        var rejected = planner(new Script(outEnv.toString()), registry(), new DagConfig()).plan("介绍亚历山大", "", "t", events::add);
        assertFalse(rejected.inDomain());
        assertEquals("超出范围", rejected.reply());
        assertTrue(rejected.plan().isEmpty());
        assertEquals(1, events.stream().filter(e -> "PLAN_DROPPED".equals(e.eventType())).count());

        ObjectNode clarifyEnv = JSON.createObjectNode();
        clarifyEnv.put("in_domain", true).put("needs_clarification", true).put("clarify", "币安还是OKX？");
        clarifyEnv.set("plan", scalarPlan());
        events = new ArrayList<>();
        var clarified = planner(new Script(clarifyEnv.toString()), registry(), new DagConfig()).plan("它的均值呢", "", "t", events::add);
        assertTrue(clarified.needsClarification());
        assertEquals("币安还是OKX？", clarified.reply());
        assertEquals(1, events.stream().filter(e -> "PLAN_DROPPED".equals(e.eventType())).count());
        var dropped = events.stream().filter(e -> "PLAN_DROPPED".equals(e.eventType())).findFirst().orElseThrow();
        assertTrue(dropped.inputJson().contains("needs_clarification=true 时 plan 必须为空"));

        // 空计划对象与 plan=null 等价：不算违规、不落事件，两个分支都是
        for (String empty : List.of("null", "{\"metrics\":[],\"calculations\":[]}")) {
            events = new ArrayList<>();
            planner(new Script("{\"in_domain\":false,\"reply\":\"超出范围\",\"plan\":" + empty + "}"), registry(), new DagConfig())
                    .plan("介绍亚历山大", "", "t", events::add);
            assertTrue(events.stream().noneMatch(e -> "PLAN_DROPPED".equals(e.eventType())), empty);
        }
    }

    @Test void 写了clarify没置旗时修复提示直指补旗() {
        // P039 型：clarify 仅在 needs_clarification=true 时生效；没置旗时服务端按规划路径处理，
        // 泛泛的"plan 不包含任何节点"会把模型引向修 plan——修复提示必须直指补旗
        var noFlag = "{\"in_domain\":true,\"clarify\":\"币安还是OKX？\",\"plan\":{\"metrics\":[],\"calculations\":[]}}";
        var flagged = "{\"in_domain\":true,\"needs_clarification\":true,\"clarify\":\"币安还是OKX？\",\"plan\":null}";
        var llm = new Script(noFlag, flagged);
        var result = planner(llm, registry(), new DagConfig()).plan("它的均值呢", "", "t", e -> {});
        assertTrue(result.needsClarification(), result.lastError());
        assertEquals("币安还是OKX？", result.reply());
        assertEquals(2, llm.calls.size());
        assertTrue(llm.calls.get(1).get(3).content().contains("needs_clarification"), "修复消息要教模型补旗");
    }

    @Test void task_ref尾部标点归一后正常命中索引() {
        // P042 型：模型输出 "task_2,"（带自然语言逗号），归一后命中 task_2，不再误变澄清
        var tasks = List.of(com.cexpilot.conversation.TaskSnapshot.candidate(firstTaskState(), "task_2"));
        var config = new DagConfig(); config.setPlannerMaxRetries(0);
        var result = planner(new Script(resumeEnvelope("task_2,")), registry(), config)
                .plan("回到最开始那个问题，换成 OKX", "", "t",
                        e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, tasks);
        assertTrue(result.plan().isPresent(), "task_ref 尾部标点应归一命中: " + result.lastError());
        assertFalse(result.needsClarification());

        // 编造引用不受归一影响：不存在的 ref 仍然澄清
        var missing = planner(new Script(resumeEnvelope("task_9")), registry(), config)
                .plan("回到最开始那个问题，换成 OKX", "", "t",
                        e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, tasks);
        assertTrue(missing.plan().isEmpty());
        assertTrue(missing.needsClarification());
    }

    // ===== 审查 R3 一期：RESUME 回访（task_ref 命中索引 → 对账基准切到所选快照） =====

    /** 与 priceHighFrame 不同的活动任务（ETH 现价快照），用于验证 RESUME 后基准确实切换。 */
    private static com.cexpilot.conversation.QueryFrame activeEthFrame() {
        var ethTarget = new com.cexpilot.metric.EffectiveTarget("m9", "metric_9", "binance",
                com.cexpilot.metric.ConditionOrigin.EXPLICIT,
                "price.last", "snapshot",
                json("{\"market_type\":\"perpetual\",\"base\":\"ETH\",\"quote\":\"USDT\"}"),
                com.cexpilot.metric.ConditionOrigin.DEFAULT,
                null, null, null, false, com.cexpilot.metric.ConditionOrigin.DEFAULT,
                null, null, null, "USDT", "ticker", "v1");
        return new com.cexpilot.conversation.QueryFrame("f2", "c1", 1, "turn-2", "ETH 现价",
                "2026-10-12T00:10:00Z", "Asia/Shanghai", List.of(ethTarget), null, null, null);
    }

    private static String resumeEnvelope(String taskRef) {
        var env = JSON.createObjectNode();
        env.put("in_domain", true).putNull("reply");
        env.set("plan", plan(metric("m1", "price.high", "range_statistic", "okx")));
        env.putArray("changes").add("exchange");
        env.put("task_action", "RESUME");
        if (taskRef != null) {
            env.put("task_ref", taskRef);
        }
        return env.toString();
    }

    @Test void RESUME命中任务索引时对账基准切到所选快照() {
        // 回访 turn-1（币安 BTC 最高价），当前活动任务是 ETH 现价；计划 okx/BTC/最高价/昨天/1h
        // 相对 turn-1 快照只换交易所 → 放行。若基准没切换（仍对 ETH 活动任务对账），
        // base/metric/shape 全是漏报，计划会被打回——本用例同时是基准切换的回归防线
        var tasks = List.of(new com.cexpilot.conversation.TaskSnapshot(1, "turn-1",
                "昨天币安 BTC 最高价", priceHighFrame()));
        var llm = new Script(resumeEnvelope("turn-1"));

        var result = planner(llm, registry(), new DagConfig()).plan("回到最开始那个问题，换成 OKX", "", "t",
                e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, tasks);

        assertTrue(result.plan().isPresent(), "RESUME 基准应为所选任务快照: " + result.lastError());
        assertFalse(result.needsClarification());
    }

    @Test void NEW不继承当前任务且任务选择随结果返回() {
        var env = JSON.createObjectNode().put("in_domain", true).put("task_action", "NEW");
        env.set("plan", scalarPlan());
        var config = new DagConfig(); config.setPlannerMaxRetries(0);
        var result = planner(new Script(env.toString()), registry(), config).plan("换话题查成交额", "", "t",
                e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, List.of());
        assertTrue(result.plan().isPresent(), result.lastError());
        assertEquals(com.cexpilot.conversation.TaskScope.Action.NEW, result.taskScope().action());
        assertNull(result.taskScope().base());
    }

    private static com.cexpilot.conversation.TaskState firstTaskState() {
        return com.cexpilot.conversation.TaskState.legacy(priceHighFrame(), 1, "昨天币安BTC最高价");
    }

    private static com.cexpilot.conversation.TaskState secondTaskState() {
        var compiled = new MetricPlanCompiler(new MetricCatalog(LOADER), registry(), catalogProviders())
                .compileFull(plan(metric("m1", "price.low", "range_statistic", "binance")), 8);
        return com.cexpilot.conversation.TaskState.complete("f1", "c1", 2, firstTaskState(),
                new com.cexpilot.conversation.ConversationService.TurnHandle(2, "turn-2", 2), "最低价呢", "SUCCEEDED",
                null, compiled, null, FOLLOWUP_CTX.requestTime(), FOLLOWUP_CTX.userZone());
    }

    @Test void RESUME可精确选择同任务最初版本而非最新版本() {
        var first = firstTaskState(); var second = secondTaskState();
        var tasks = List.of(com.cexpilot.conversation.TaskSnapshot.candidate(first, "task_1"),
                com.cexpilot.conversation.TaskSnapshot.candidate(second, "task_1"));
        var env = (ObjectNode) json(resumeEnvelope("task_1")); env.put("state_ref", "task_1_v1");
        var config = new DagConfig(); config.setPlannerMaxRetries(0);
        var result = planner(new Script(env.toString()), registry(), config).plan("回到第一轮换成OKX", "", "t",
                e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, tasks);
        assertTrue(result.plan().isPresent(), result.lastError());
        assertEquals(first, result.taskScope().base());
        assertEquals(1, result.taskScope().baseVersion());
    }

    @Test void RESUME不指定版本时选择该任务最新版本() {
        var first = firstTaskState(); var second = secondTaskState();
        var tasks = List.of(com.cexpilot.conversation.TaskSnapshot.candidate(first, "task_1"),
                com.cexpilot.conversation.TaskSnapshot.candidate(second, "task_1"));
        var env = (ObjectNode) json(resumeEnvelope("task_1"));
        ((ObjectNode)env.at("/plan/metrics/0")).put("metric", "price.low");
        var config = new DagConfig(); config.setPlannerMaxRetries(0);
        var result = planner(new Script(env.toString()), registry(), config).plan("回到之前任务换成OKX", "", "t",
                e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, tasks);
        assertTrue(result.plan().isPresent(), result.lastError());
        assertEquals(second, result.taskScope().base());
    }

    @Test void 历史任务与版本必须属于同一个候选任务() {
        var tasks = List.of(com.cexpilot.conversation.TaskSnapshot.candidate(firstTaskState(), "task_1"),
                com.cexpilot.conversation.TaskSnapshot.candidate(com.cexpilot.conversation.TaskState.legacy(activeEthFrame(), 3, "ETH现价"), "task_2"));
        var env = (ObjectNode) json(resumeEnvelope("task_1")); env.put("state_ref", "task_2_v1");
        var result = planner(new Script(env.toString()), registry(), new DagConfig()).plan("回到之前任务", "", "t",
                e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, tasks);
        assertTrue(result.needsClarification()); assertTrue(result.plan().isEmpty());
    }

    @Test void 澄清时也返回已选择的任务和来源版本() {
        var current = firstTaskState();
        String response = "{\"in_domain\":true,\"task_action\":\"CONTINUE\",\"needs_clarification\":true,\"clarify\":\"哪家交易所？\",\"plan\":null}";
        var result = planner(new Script(response), registry(), new DagConfig()).plan("币安", "", "t", e -> {},
                current.frame(), FOLLOWUP_CTX, null, List.of(), current);
        assertTrue(result.needsClarification()); assertEquals(current, result.taskScope().base());
        assertEquals(com.cexpilot.conversation.TaskScope.Action.CONTINUE, result.taskScope().action());
    }

    @Test void 历史选择连续澄清也保留原待补任务() {
        var pending = com.cexpilot.conversation.TaskState.complete("pending", "c1", 1, null,
                new com.cexpilot.conversation.ConversationService.TurnHandle(1, "turn-1", 1),
                "回到之前那个BTC问题", "NEEDS_CLARIFICATION", "您指哪一次查询？", null, null,
                FOLLOWUP_CTX.requestTime(), FOLLOWUP_CTX.userZone());
        var result = planner(new Script(resumeEnvelope("missing")), registry(), new DagConfig()).plan("最早那个", "", "t",
                e -> {}, null, FOLLOWUP_CTX, null, List.of(), pending);
        assertTrue(result.needsClarification());
        assertEquals(pending, result.taskScope().base());
        assertEquals(com.cexpilot.conversation.TaskScope.Action.CONTINUE, result.taskScope().action());
    }

    @Test void RESUME引用不在索引中时打回修复() {
        var config = new DagConfig();
        config.setPlannerMaxRetries(0);
        var llm = new Script(resumeEnvelope("turn-99"));

        var result = planner(llm, registry(), config).plan("回到最开始那个问题", "", "t",
                e -> {}, activeEthFrame(), FOLLOWUP_CTX, null, List.of());

        assertTrue(result.plan().isEmpty(), "索引中不存在的 task_ref 不得执行");
        assertTrue(result.needsClarification());
        assertTrue(result.reply().contains("task_ref"), result.reply());
    }
}
