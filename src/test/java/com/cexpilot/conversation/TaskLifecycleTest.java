package com.cexpilot.conversation;

import com.cexpilot.config.ConversationConfig;
import com.cexpilot.dag.DagPlan;
import com.cexpilot.metric.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final InMemoryTaskRepository repo = new InMemoryTaskRepository();
    private final ConversationService service = new ConversationService(repo, new ConversationConfig());
    private final ConversationContextService context = new ConversationContextService(repo);

    private static CompiledPlan plan(String metric, String base, String exchange) {
        var instrument = JSON.createObjectNode().put("market_type", "perpetual").put("base", base)
                .put("quote", "USDT").put("settle", "USDT");
        boolean funding = metric.equals("funding.rate_settled");
        String shape = funding ? "recent_n" : "snapshot";
        var json = JSON.createObjectNode();
        var node = json.putArray("metrics").addObject().put("id", "m1").put("metric", metric)
                .put("query_shape", shape);
        if (funding) node.put("count", 10);
        node.putArray("exchanges").add(exchange); node.set("instrument", instrument);
        json.putArray("calculations");
        var target = new EffectiveTarget("m1", "metric_0", exchange, ConditionOrigin.EXPLICIT, metric,
                shape, instrument, ConditionOrigin.DEFAULT, null, null, null, false, null,
                funding ? 10 : null, null, null, funding ? "decimal_rate" : "USDT", funding ? "funding" : "ticker", "v1");
        return new CompiledPlan(new LogicalPlan(List.of(), List.of()), json, new DagPlan(List.of()),
                Map.of("m1", Map.of(exchange, "metric_0")), List.of(target));
    }
    private TaskState finish(String question, String status, CompiledPlan plan, TaskScope scope, String response) {
        var turn = service.beginTurn("c1", question, null, NOW);
        return service.finishTaskTurn("c1", turn, question, status, "answer", "trace", plan, null, scope,
                NOW, SH, response).state();
    }
    private TaskState fresh(String question, String base) {
        return finish(question, "SUCCEEDED", plan("price.last", base, "binance"), new TaskScope(TaskScope.Action.NEW, null), null);
    }

    @Test void 结构化填写值随未完成任务持久化且修改覆盖旧值() {
        var pending = finish("比较平均Funding", "NEEDS_CLARIFICATION", null,
                new TaskScope(TaskScope.Action.NEW, null), "请填币种和期数");
        var supplied = pending.withClarificationAnswers(List.of(new ClarificationAnswer("base", "币种", "比特币"),
                new ClarificationAnswer("count", "期数", "200")));
        var correction = finish("币种比特币，期数200", "NEEDS_CLARIFICATION", null, TaskScope.current(supplied), "期数最多100，请修改");
        assertEquals(supplied.clarificationAnswers(), correction.clarificationAnswers());
        assertEquals(correction, TaskState.fromJson(correction.toJson()));
        assertTrue(context.buildPlannerInput("c1").contextText().contains("count（期数）=200"));
        var input = context.buildPlannerInput("c1").withClarificationAnswers(List.of(new ClarificationAnswer("count", "期数", "10")));
        assertTrue(input.contextText().contains("base（币种）=比特币"));
        assertTrue(input.contextText().contains("count（期数）=10"));
        assertFalse(input.contextText().contains("count（期数）=200"));
        var done = finish("改成10期", "SUCCEEDED", plan("funding.rate_settled", "BTC", "binance"), TaskScope.current(input.currentTask()), null);
        assertTrue(done.clarificationAnswers().isEmpty());
    }

    @Test void 多选值随任务版本保存和更新且旧字符串快照仍可读取() throws Exception {
        var pending=finish("两所Funding比较","NEEDS_CLARIFICATION",null,new TaskScope(TaskScope.Action.NEW,null),"请选择两家");
        var values=JSON.readTree("[\"币安\",\"OKX\"]");
        var supplied=pending.withClarificationAnswers(List.of(new ClarificationAnswer("exchange","交易所",values)));
        var next=finish("币安和OKX","NEEDS_CLARIFICATION",null,TaskScope.current(supplied),"请补期数");
        assertEquals(next,TaskState.fromJson(next.toJson()));
        assertTrue(context.buildPlannerInput("c1").currentTask().clarificationAnswers().get(0).value().isArray());
        var legacy=JSON.readTree(next.toJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)legacy.at("/clarificationAnswers/0")).put("value","币安");
        assertEquals("币安",TaskState.fromJson(legacy.toString()).clarificationAnswers().get(0).value().asText());
    }

    @Test void 同一任务追问稳定ID并逐轮追加版本() {
        TaskState a = fresh("BTC 当前价", "BTC");
        TaskState b = finish("那 ETH 呢", "SUCCEEDED", plan("price.last", "ETH", "binance"), TaskScope.current(a), null);
        assertEquals(a.frameId(), b.frameId()); assertEquals(2, b.version());
        assertEquals(1, repo.tasks.size()); assertEquals(2, repo.snapshots.size());
        assertEquals("BTC 当前价", b.originQuestion());
        assertEquals(a.frameId(), b.frame().frameId()); assertEquals(2, b.frame().version());
        assertEquals("BTC", repo.firstTaskVersion("c1", a.frameId()).frame().targets().get(0).instrument().path("base").asText());
    }

    @Test void 切换任务保留旧任务且回访旧版本追加最新版本() {
        TaskState first = fresh("BTC 当前价", "BTC");
        TaskState second = finish("那 ETH 呢", "SUCCEEDED", plan("price.last", "ETH", "binance"), TaskScope.current(first), null);
        TaskState other = fresh("查 SOL", "SOL");
        TaskState resumed = finish("回到最开始那个问题换成OKX", "SUCCEEDED", plan("price.last", "BTC", "okx"),
                new TaskScope(TaskScope.Action.RESUME, first), null);
        assertEquals(first.frameId(), resumed.frameId()); assertEquals(3, resumed.version());
        assertEquals(other, repo.findTask("c1", other.frameId()));
        assertEquals("ETH", repo.snapshots.get(second.turnId()).frame().targets().get(0).instrument().path("base").asText());
        assertEquals(1, repo.scopes.get(resumed.turnId()).baseVersion());
        assertEquals(resumed.frameId(), context.buildPlannerInput("c1").currentTask().frameId());
    }

    @Test void 六轮换话题后最初任务和最初版本仍在索引() {
        TaskState first = fresh("最初的 BTC 查询", "BTC");
        for (int i = 0; i < 6; i++) fresh("其他话题" + i, "ETH");
        PlannerInput input = context.buildPlannerInput("c1");
        assertTrue(input.tasks().stream().anyMatch(t -> t.state().frameId().equals(first.frameId()) && t.state().version() == 1));
        assertTrue(input.contextText().contains("最初的 BTC 查询"));
        assertEquals(7, repo.tasks.size());
    }

    @Test void 任务内超过五轮追问也保留最初版本() {
        TaskState first = fresh("BTC 当前价", "BTC");
        TaskState latest = first;
        for (int i = 0; i < 7; i++) latest = finish("刷新" + i, "SUCCEEDED", plan("price.last", "ETH", "binance"), TaskScope.current(latest), null);
        PlannerInput input = context.buildPlannerInput("c1");
        assertEquals(1, repo.tasks.size());
        assertTrue(input.tasks().stream().anyMatch(t -> t.state().version() == 1));
        assertTrue(input.tasks().stream().anyMatch(t -> t.state().version() == 8));
    }

    @Test void 表单补充写入任务链并保留原交易所和比较目标() throws Exception {
        var fields = com.cexpilot.dag.ClarificationField.parse(JSON.readTree("""
                [{"key":"base","label":"币种","type":"text"},
                 {"key":"count","label":"期数","type":"number","hint":"比较最近多少期的平均费率","min":1,"max":100}]
                """));
        String question = "比较币安和OKX最近几期已结算Funding的平均值";
        TaskState pending = finish(question, "NEEDS_CLARIFICATION", null,
                new TaskScope(TaskScope.Action.NEW, null), com.cexpilot.dag.ClarificationField.question(fields));
        TaskState partial = finish("补充本轮查询条件：\n币种：BTC", "NEEDS_CLARIFICATION", null,
                TaskScope.current(pending), "请补充期数");
        String text = context.buildPlannerInput("c1").contextText();
        assertTrue(text.contains(question));
        assertTrue(text.contains("币种：BTC"));
        assertTrue(text.contains("期数"));
        assertEquals(pending.frameId(), partial.frameId());
        TaskState done = finish("补充本轮查询条件：\n期数（比较最近多少期的平均费率）：10", "SUCCEEDED",
                plan("funding.rate_settled", "BTC", "binance"), TaskScope.current(partial), null);
        assertEquals(question, done.originQuestion());
        assertEquals(pending.frameId(), done.frameId());
        assertTrue(done.dialogue().isEmpty());
    }

    @Test void 首轮连续澄清保留原问题和全部补充条件() {
        TaskState p1 = finish("查 BTC 最近几期资金费率", "NEEDS_CLARIFICATION", null,
                new TaskScope(TaskScope.Action.NEW, null), "哪家交易所？");
        TaskState p2 = finish("币安", "NEEDS_CLARIFICATION", null, TaskScope.current(p1), "最近多少期？");
        String text = context.buildPlannerInput("c1").contextText();
        assertTrue(text.contains("BTC 最近几期资金费率")); assertTrue(text.contains("币安"));
        assertTrue(text.contains("最近多少期")); assertEquals(2, p2.dialogue().size());
        assertEquals(p1.frameId(), p2.frameId()); assertEquals(2, p2.version());
        TaskState done = finish("10期", "SUCCEEDED", plan("funding.rate_settled", "BTC", "binance"), TaskScope.current(p2), null);
        assertEquals(p1.frameId(), done.frameId()); assertEquals(3, done.version());
        assertTrue(done.dialogue().isEmpty()); assertEquals("查 BTC 最近几期资金费率", done.originQuestion());
        assertEquals(10, done.frame().targets().get(0).count());
    }

    @Test void 无计划的解释性回答不清空未完成条件链() {
        TaskState first = finish("查 BTC 资金费率", "NEEDS_CLARIFICATION", null,
                new TaskScope(TaskScope.Action.NEW, null), "哪家交易所？");
        TaskState exchange = finish("币安", "NEEDS_CLARIFICATION", null, TaskScope.current(first), "多少期？");
        TaskState explanation = finish("为什么需要期数？", "SUCCEEDED", null, TaskScope.current(exchange), "期数决定样本数量");
        assertEquals("NEEDS_CLARIFICATION", explanation.status());
        assertEquals(3, explanation.dialogue().size());
        assertTrue(context.buildPlannerInput("c1").contextText().contains("币安"));
        TaskState complete = finish("10期", "SUCCEEDED", plan("funding.rate_settled", "BTC", "binance"), TaskScope.current(explanation), null);
        assertEquals(4, complete.version()); assertEquals(first.frameId(), complete.frameId());
        assertTrue(complete.dialogue().isEmpty());
    }

    @Test void 新任务澄清不会挂在旧成功任务且可回到旧任务() {
        TaskState old = fresh("ETH 价格", "ETH");
        TaskState pending = finish("查 BTC 资金费率", "NEEDS_CLARIFICATION", null,
                new TaskScope(TaskScope.Action.NEW, null), "多少期？");
        assertNotEquals(old.frameId(), pending.frameId());
        assertNull(context.buildPlannerInput("c1").activeFrame());
        assertEquals(old, repo.findTask("c1", old.frameId()));
        assertEquals(pending.frameId(), context.buildPlannerInput("c1").currentTask().frameId());
    }

    @Test void 连续失败重试保留有效计划及原任务而非只剩再试一次() {
        fresh("BTC 当前价", "BTC");
        TaskState failed = finish("ETH 当前价", "FAILED", plan("price.last", "ETH", "binance"),
                new TaskScope(TaskScope.Action.NEW, null), "Answer 超时");
        TaskState retry = finish("再试一次", "FAILED", null, TaskScope.current(failed), "Planner 超时");
        assertEquals(failed.frameId(), retry.frameId()); assertEquals(2, retry.version());
        assertEquals(failed.logicalPlan(), retry.logicalPlan());
        PlannerInput input = context.buildPlannerInput("c1");
        assertEquals("ETH", input.activeFrame().targets().get(0).instrument().path("base").asText());
        assertTrue(input.contextText().contains("ETH 当前价")); assertTrue(input.contextText().contains("再试一次"));
        TaskState done = finish("那 OKX 呢", "SUCCEEDED", plan("price.last", "ETH", "okx"), TaskScope.current(retry), null);
        assertEquals(failed.frameId(), done.frameId()); assertEquals(3, done.version());
        assertEquals("ETH", done.successfulFrame().targets().get(0).instrument().path("base").asText());
    }

    @Test void 同任务失败保留最近成功结果并展示失败有效条件() {
        TaskState success = fresh("BTC 当前价", "BTC");
        TaskState failed = finish("那 ETH 呢", "FAILED", plan("price.last", "ETH", "binance"), TaskScope.current(success), "Answer 超时");
        assertEquals("BTC", failed.successfulFrame().targets().get(0).instrument().path("base").asText());
        assertEquals("ETH", failed.frame().targets().get(0).instrument().path("base").asText());
    }

    @Test void 回访旧版本失败不能倒退最近成功状态() {
        TaskState first = fresh("BTC 当前价", "BTC");
        TaskState latest = finish("那 ETH 呢", "SUCCEEDED", plan("price.last", "ETH", "binance"), TaskScope.current(first), null);
        TaskState failed = finish("回到最初BTC再试", "FAILED", plan("price.last", "BTC", "binance"),
                new TaskScope(TaskScope.Action.RESUME, first), "Answer 超时");
        assertEquals(3, failed.version());
        assertEquals("BTC", failed.frame().targets().get(0).instrument().path("base").asText());
        assertEquals(latest.successfulFrame(), failed.successfulFrame());
        assertEquals(1, repo.scopes.get(failed.turnId()).baseVersion());
    }

    @Test void 连续失败后最近成功版本仍可回访() {
        TaskState first = fresh("BTC 当前价", "BTC");
        TaskState success = finish("那 ETH 呢", "SUCCEEDED", plan("price.last", "ETH", "binance"), TaskScope.current(first), null);
        TaskState failed = success;
        for (int i = 0; i < 6; i++) failed = finish("查回 BTC", "FAILED", plan("price.last", "BTC", "binance"), TaskScope.current(failed), "Answer超时");
        assertTrue(context.buildPlannerInput("c1").tasks().stream().anyMatch(t -> t.state().version() == success.version()
                && t.state().status().equals("SUCCEEDED")));
    }

    @Test void 最初任务先澄清再完成时首个有效查询版本仍保留() {
        TaskState first = finish("查价格", "NEEDS_CLARIFICATION", null, new TaskScope(TaskScope.Action.NEW, null), "哪个币？");
        TaskState complete = finish("BTC", "SUCCEEDED", plan("price.last", "BTC", "binance"), TaskScope.current(first), null);
        TaskState latest = complete;
        for (int i = 0; i < 6; i++) latest = finish("那 ETH 呢", "SUCCEEDED", plan("price.last", "ETH", "binance"), TaskScope.current(latest), null);
        assertTrue(context.buildPlannerInput("c1").tasks().stream().anyMatch(t -> t.state().version() == complete.version()));
    }

    @Test void 陈旧fencingToken不得修改任何任务或指针() {
        TaskState old = fresh("BTC 当前价", "BTC");
        var turn = service.beginTurn("c1", "那 ETH 呢", null, NOW); repo.acceptCommit = false;
        var commit = service.finishTaskTurn("c1", turn, "那 ETH 呢", "SUCCEEDED", "answer", "trace",
                plan("price.last", "ETH", "binance"), null, TaskScope.current(old), NOW, SH, null);
        assertFalse(commit.updated()); assertEquals(old, repo.findActiveTask("c1"));
        assertEquals(1, repo.snapshots.size());
    }

    @Test void 尚无完整frame的澄清任务也保留原时区() {
        var turn = service.beginTurn("c1", "查昨天成交额", null, NOW);
        TaskState pending = service.finishTaskTurn("c1", turn, "查昨天成交额", "NEEDS_CLARIFICATION", null, "trace",
                null, null, new TaskScope(TaskScope.Action.NEW, null), NOW, ZoneId.of("America/New_York"), "哪个币？").state();
        assertNull(pending.frame());
        var selected = TaskScope.current(pending).executionContext(new com.cexpilot.runtime.RequestContext(SH, NOW.plusSeconds(60)));
        assertEquals(ZoneId.of("America/New_York"), selected.userZone());
        assertEquals(NOW.plusSeconds(60), selected.requestTime());
        assertTrue(context.buildPlannerInput("c1").contextText().contains("America/New_York"));
    }

    @Test void 任务状态JSON往返保留版本逻辑计划和补充链() {
        TaskState state = finish("ETH 当前价", "FAILED", plan("price.last", "ETH", "binance"),
                new TaskScope(TaskScope.Action.NEW, null), "失败");
        assertEquals(state, TaskState.fromJson(state.toJson()));
    }
}
