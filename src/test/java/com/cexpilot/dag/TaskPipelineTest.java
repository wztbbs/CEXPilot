package com.cexpilot.dag;

import com.cexpilot.api.AskService;
import com.cexpilot.config.*;
import com.cexpilot.conversation.*;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.cexpilot.trace.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import java.time.*;
import java.util.*;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 全部 LLM/Provider 都是本地桩；真实编排、编译、DAG、事实与任务状态流转。 */
class TaskPipelineTest {
    private static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    private static String price(String action, String base, String exchange, String task, String state) {
        var metric = MetricTestSupport.metric("m1", "price.last", "snapshot", exchange);
        metric.remove("time"); metric.remove("interval");
        ((com.fasterxml.jackson.databind.node.ObjectNode)metric.get("instrument")).put("base", base);
        var env = (com.fasterxml.jackson.databind.node.ObjectNode)json(envelope(plan(metric)));
        env.put("task_action", action);
        if (task != null) env.put("task_ref", task);
        if (state != null) env.put("state_ref", state);
        env.putArray("changes").add("exchange");
        return env.toString();
    }
    private static String clarify(String action, String question) {
        return JSON.createObjectNode().put("in_domain", true).put("task_action", action)
                .put("needs_clarification", true).put("clarify", question).putNull("plan").toString();
    }

    @Test void 连续澄清失败重试及跨话题回访使用同一任务版本链() {
        var repo = new InMemoryTaskRepository() {
            @Override public boolean conversationExists(String id) { return true; }
        };
        var conversations = new ConversationService(repo, new ConversationConfig());
        var contexts = new ConversationContextService(repo);
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var llm = new Script(clarify("NEW", "哪家交易所？"), clarify("CONTINUE", "哪个币？"),
                price("CONTINUE", "BTC", "binance", null, null), "BTC 答案",
                price("NEW", "ETH", "binance", null, null), "ERROR", "ERROR",
                price("CONTINUE", "ETH", "okx", null, null), "ETH 答案") {
            @Override public com.cexpilot.llm.ChatResponse chat(List<com.cexpilot.llm.ChatMessage> messages,
                    List<com.cexpilot.llm.ToolSpec> tools) {
                if ("ERROR".equals(outputs.peek())) { outputs.remove(); throw new IllegalStateException("模拟超时"); }
                return super.chat(messages, tools);
            }
        };
        var ticker = namedProvider(TickerMetricProvider.NAME, TickerMetric::valueOf, (q,c) ->
                new MetricResult.Point(new MetricResult.SnapshotMetadata(c.requestTime(), c.userZone()),
                        new java.math.BigDecimal("90000")));
        var config = new DagConfig();
        var executor = new DagExecutor(registry(), config, providers(ticker));
        try {
            var runtime = new DagRuntime(llm, planner(llm, registry(), config), executor,
                    new PromptStore(LOADER), Clock.fixed(NOW, ZoneOffset.UTC), store);
            var ask = new AskService(conversations, contexts, runtime, mock(TraceRepository.class),
                    mock(DbTraceSink.class), new LlmConfig(), Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(ObjectProvider.class));

            ask.ask("c1", "查询合约价格", "v", "Asia/Shanghai", null, null);
            TaskState a1 = repo.findActiveTask("c1");
            assertEquals("NEEDS_CLARIFICATION", a1.status());
            ask.ask("c1", "币安", "v", "Asia/Shanghai", null, null);
            assertEquals(a1.frameId(), repo.findActiveTask("c1").frameId());
            assertEquals(2, repo.findActiveTask("c1").dialogue().size());
            ask.ask("c1", "BTC", "v", "Asia/Shanghai", null, null);
            TaskState a3 = repo.findActiveTask("c1");
            assertEquals(3, a3.version()); assertEquals("SUCCEEDED", a3.status());
            assertEquals("查询合约价格", a3.originQuestion());

            assertThrows(DagRuntime.ExecutionFailure.class,
                    () -> ask.ask("c1", "换个话题查ETH当前价", "v", "Asia/Shanghai", null, null));
            TaskState b1 = repo.findActiveTask("c1");
            assertNotEquals(a3.frameId(), b1.frameId()); assertEquals("FAILED", b1.status());
            assertEquals("ETH", b1.frame().targets().get(0).instrument().path("base").asText());
            assertFalse(b1.frame().focus().path("evidence_nodes").get(0).path("fact_id").asText().isBlank());
            assertThrows(DagRuntime.ExecutionFailure.class,
                    () -> ask.ask("c1", "再试一次", "v", "Asia/Shanghai", null, null));
            TaskState b2 = repo.findActiveTask("c1");
            assertEquals(b1.frameId(), b2.frameId()); assertEquals(2, b2.version());
            assertEquals(b1.logicalPlan(), b2.logicalPlan()); assertEquals(2, b2.dialogue().size());
            ask.ask("c1", "那OKX呢", "v", "Asia/Shanghai", null, null);
            TaskState b3 = repo.findActiveTask("c1");
            assertEquals(b1.frameId(), b3.frameId()); assertEquals(3, b3.version());
            assertEquals("ETH", b3.frame().targets().get(0).instrument().path("base").asText());
            assertEquals("okx", b3.frame().targets().get(0).exchange());

            for (int i = 0; i < 6; i++) {
                llm.outputs.add(price("NEW", "SOL", "binance", null, null)); llm.outputs.add("SOL 答案");
                ask.ask("c1", "其他话题" + i, "v", "Asia/Shanghai", null, null);
            }
            var target = contexts.buildPlannerInput("c1").tasks().stream()
                    .filter(t -> t.state().frameId().equals(a3.frameId()) && t.state().version() == 3)
                    .findFirst().orElseThrow();
            llm.outputs.add(price("RESUME", "BTC", "okx", target.taskRef(), target.stateRef())); llm.outputs.add("BTC 回访答案");
            ask.ask("c1", "回到之前的BTC任务换成OKX", "v", "Asia/Shanghai", null, null);
            TaskState resumed = repo.findActiveTask("c1");
            assertEquals(a3.frameId(), resumed.frameId()); assertEquals(4, resumed.version());
            assertEquals(3, repo.scopes.get(resumed.turnId()).baseVersion());
            assertEquals("BTC", resumed.frame().targets().get(0).instrument().path("base").asText());
            assertEquals("okx", resumed.frame().targets().get(0).exchange());
            assertEquals(b3.toJson(), repo.findTask("c1", b3.frameId()).toJson());
        } finally { executor.shutdown(); }
    }

    @Test void 自动粒度追问重新选择而用户后来指定的固定粒度继续沿用() {
        var repo = new InMemoryTaskRepository() {
            @Override public boolean conversationExists(String id) { return true; }
        };
        var conversations = new ConversationService(repo, new ConversationConfig());
        var contexts = new ConversationContextService(repo);
        var store = new com.cexpilot.evidence.EvidenceStore(new com.cexpilot.evidence.InMemoryEvidenceRepository());
        var first = scalarPlan(); ((com.fasterxml.jackson.databind.node.ObjectNode)first.at("/metrics/0")).remove("interval");
        var copied = scalarPlan(); ((com.fasterxml.jackson.databind.node.ObjectNode)copied.at("/metrics/0")).put("interval", "5m");
        var rolling = json("{\"type\":\"rolling_window\",\"duration\":{\"value\":30,\"unit\":\"day\"}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)copied.at("/metrics/0")).set("time", rolling);
        var continued = (com.fasterxml.jackson.databind.node.ObjectNode)json(envelope(copied));
        continued.put("task_action", "CONTINUE"); continued.putArray("changes").add("time");
        var explicit = continued.deepCopy();
        explicit.putObject("explicit_conditions").putObject("m1").put("interval", "5分钟");
        explicit.withArray("changes").add("interval");
        var omitted = (com.fasterxml.jackson.databind.node.ObjectNode)json(envelope(first));
        omitted.put("task_action", "CONTINUE"); omitted.putArray("changes").add("time");
        var llm = new Script(envelope(first), "首答", continued.toString(), "追问答案",
                explicit.toString(), "固定粒度答案", omitted.toString(), "沿用粒度答案");
        List<String> receivedIntervals = new ArrayList<>();
        var provider = provider((q,c) -> {
            var tq = (TimeRangeQuery)q;
            receivedIntervals.add(tq.intervalCode());
            var range = new com.cexpilot.time.TimeRangeResolver(Clock.fixed(c.requestTime(), c.userZone()))
                    .resolve(c.userZone(), tq.time(), c.requestTime());
            String interval = tq.intervalCode() != null ? tq.intervalCode()
                    : Duration.between(range.startInclusive(), range.endExclusive()).toDays() > 7 ? "1h" : "5m";
            var coverage = new com.cexpilot.market.series.SeriesCoverage(1,1,List.of(),List.of(),List.of(),false,false,
                    null,range.endExclusive().toEpochMilli(),false);
            return new MetricResult.Scalar(new MetricResult.SeriesMetadata(range, range, coverage, interval,
                    tq.intervalCode() == null, 1), java.math.BigDecimal.TEN, java.math.BigDecimal.valueOf(86400), range);
        });
        var config = new DagConfig();
        var executor = new DagExecutor(registry(), config, providers(provider));
        try {
            var runtime = new DagRuntime(llm, planner(llm, registry(), config), executor,
                    new PromptStore(LOADER), Clock.fixed(NOW, ZoneOffset.UTC), store);
            var ask = new AskService(conversations, contexts, runtime, mock(TraceRepository.class),
                    mock(DbTraceSink.class), new LlmConfig(), Clock.fixed(NOW, ZoneOffset.UTC), mock(ObjectProvider.class));
            var a = ask.ask("c1", "昨天币安BTC成交额", "v", "Asia/Shanghai", "", null);
            var firstState = repo.findActiveTask("c1");
            assertNull(firstState.frame().targets().get(0).interval());
            assertEquals("5m", firstState.frame().targets().get(0).executionMetadata().path("candle_interval").asText());
            var b = ask.ask("c1", "换成最近30天", "v", "Asia/Shanghai", a.turnId(), null);
            var secondState = repo.findActiveTask("c1");
            assertEquals(firstState.frameId(), secondState.frameId());
            assertNull(secondState.frame().targets().get(0).interval());
            assertEquals("1h", secondState.frame().targets().get(0).executionMetadata().path("candle_interval").asText());
            var c = ask.ask("c1", "固定用5分钟粒度", "v", "Asia/Shanghai", b.turnId(), null);
            var thirdState = repo.findActiveTask("c1");
            assertEquals(ConditionOrigin.EXPLICIT, thirdState.frame().targets().get(0).intervalOrigin());
            ask.ask("c1", "再换回昨天", "v", "Asia/Shanghai", c.turnId(), null);
            var finalTarget = repo.findActiveTask("c1").frame().targets().get(0);
            assertEquals("5m", finalTarget.interval());
            assertEquals(ConditionOrigin.INHERITED, finalTarget.intervalOrigin());
            assertEquals(c.turnId(), finalTarget.conditionSources().get("interval").sourceTurnId());
            assertEquals(3L, finalTarget.conditionSources().get("interval").sourceVersion());
            assertEquals(Arrays.asList(null, null, "5m", "5m"), receivedIntervals);
            assertEquals(8, llm.calls.size()); // 每轮一次 Planner + 一次 Answer，未因来源补齐而增加 LLM 调用。
        } finally { executor.shutdown(); }
    }
}
