package com.cexpilot.conversation;

import com.cexpilot.metric.EffectiveTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 结构化上下文组装（多轮追问阶段一 c）：加载活动 QueryFrame + 近期文本历史，
 * 分层渲染成 Planner 的 conversation_context；activeFrame 本体同时供程序侧继承对账使用。
 * 阶段二追加【可引用历史事实】段（EvidenceAssembler 候选索引，只含别名与身份摘要，不含明细）。
 *
 * 降级原则：frame 读不到或反序列化失败时退回纯文本历史（旧行为），只记日志，不让老会话报错。
 * Answer 侧仍用 ConversationService.renderContext 的纯文本口径，本类不影响 Answer 输入。
 */
@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    private static final int HISTORY_QUERY_LIMIT = 5;
    // Planner 需要看清上一轮的口径与结论才能正确继承，截断太狠会丢指代；故比 Answer 侧（300）放宽。
    // 只裁剪文本层，结构化条件与可引用事实段不参与截断（预算不足时优先裁文本历史）。
    private static final int HISTORY_ANSWER_MAX_CHARS = 500;
    // 任务索引容量（R3 一期）：可回访任务的有限保留，超出范围澄清不硬猜
    private static final int TASK_INDEX_LIMIT = 5;
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ConversationRepository repository;
    // 阶段二：历史事实存取；null 时不渲染可引用事实段（单轮旧行为）
    private final com.cexpilot.evidence.EvidenceStore evidenceStore;

    public ConversationContextService(ConversationRepository repository) {
        this(repository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationContextService(ConversationRepository repository,
                                      com.cexpilot.evidence.EvidenceStore evidenceStore) {
        this.repository = repository;
        this.evidenceStore = evidenceStore;
    }

    public PlannerInput buildPlannerInput(String conversationId) {
        QueryFrame frame = loadActiveFrame(conversationId);
        List<TaskSnapshot> tasks = loadTasks(conversationId);
        List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence =
                loadEvidence(conversationId, focusFactIds(frame));
        TextHistory history = renderTextHistory(conversationId);
        com.fasterxml.jackson.databind.JsonNode pendingTask = loadStateJson(
                repository.findPendingTaskJson(conversationId), "pending_task");
        com.fasterxml.jackson.databind.JsonNode lastAttempt = loadStateJson(
                repository.findLastAttemptJson(conversationId), "last_attempt");
        return new PlannerInput(render(frame, history.text(), evidence, pendingTask, lastAttempt, tasks),
                frame, evidence, history.count(), tasks);
    }

    /** 近期任务索引：带 frame 快照的成功轮次，供 RESUME 回访（R3 一期）。读取失败降级为空。 */
    private List<TaskSnapshot> loadTasks(String conversationId) {
        try {
            List<TaskSnapshot> tasks = repository.recentFrames(conversationId, TASK_INDEX_LIMIT);
            return tasks == null ? List.of() : tasks;
        } catch (Exception e) {
            log.warn("任务索引读取失败，本轮无可回访任务 conversationId={}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    /** 活动 frame 焦点引用的 factId 集合（R12）：事实候选排序时排最前。 */
    private static java.util.Set<String> focusFactIds(QueryFrame frame) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (frame == null || frame.focus() == null) {
            return ids;
        }
        for (com.fasterxml.jackson.databind.JsonNode node : frame.focus().path("evidence_nodes")) {
            String factId = node.path("fact_id").asText(null);
            if (factId != null && !factId.isBlank()) {
                ids.add(factId);
            }
        }
        return ids;
    }

    /** 会话状态列的 JSON 读取：损坏时降级为 null 只记日志。 */
    private com.fasterxml.jackson.databind.JsonNode loadStateJson(String json, String name) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            log.warn("会话状态 {} 反序列化失败，本轮忽略 {}: {}", name, name, e.getMessage());
            return null;
        }
    }

    /** 可引用历史事实：未过期且有缓存 payload 的 FACT，焦点事实（R12）排最前、其余按取数时间倒序分配稳定短别名。读不到就空列表。 */
    private List<com.cexpilot.evidence.EvidenceAssembler.Entry> loadEvidence(
            String conversationId, java.util.Set<String> focusFactIds) {
        if (evidenceStore == null) {
            return List.of();
        }
        try {
            return com.cexpilot.evidence.EvidenceAssembler.assignRefs(
                    evidenceStore.listReusable(conversationId, java.time.Instant.now()), focusFactIds);
        } catch (Exception e) {
            log.warn("历史事实读取失败，本轮按无可引用事实处理 conversationId={}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    private QueryFrame loadActiveFrame(String conversationId) {
        String json = repository.findActiveFrameJson(conversationId);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return QueryFrame.fromJson(json);
        } catch (Exception e) {
            log.warn("活动 frame 反序列化失败，本轮降级为纯文本历史 conversationId={}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    /** 近期问答文本 + 条数。 */
    private record TextHistory(String text, int count) {
    }

    /** 近期问答文本：带每轮发生时间（缓解跨天窗口漂移），答案截断放宽到 500。 */
    private TextHistory renderTextHistory(String conversationId) {
        List<QueryRecord> queries = repository.recentQueries(conversationId, HISTORY_QUERY_LIMIT);
        if (queries.isEmpty()) {
            return new TextHistory("", 0);
        }
        StringBuilder sb = new StringBuilder();
        for (QueryRecord query : queries) {
            sb.append("用户（").append(query.createdAt().format(HISTORY_TIME)).append("）：")
                    .append(query.question()).append('\n');
            if (query.answer() != null) {
                sb.append("助手：").append(abbreviate(query.answer(), HISTORY_ANSWER_MAX_CHARS)).append('\n');
            }
        }
        return new TextHistory(sb.toString(), queries.size());
    }

    static String render(QueryFrame frame, String textHistory) {
        return render(frame, textHistory, List.of(), null, null, List.of());
    }

    static String render(QueryFrame frame, String textHistory,
                         List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence) {
        return render(frame, textHistory, evidence, null, null, List.of());
    }

    static String render(QueryFrame frame, String textHistory,
                         List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence,
                         com.fasterxml.jackson.databind.JsonNode pendingTask,
                         com.fasterxml.jackson.databind.JsonNode lastAttempt) {
        return render(frame, textHistory, evidence, pendingTask, lastAttempt, List.of());
    }

    static String render(QueryFrame frame, String textHistory,
                         List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence,
                         com.fasterxml.jackson.databind.JsonNode pendingTask,
                         com.fasterxml.jackson.databind.JsonNode lastAttempt,
                         List<TaskSnapshot> tasks) {
        StringBuilder sb = new StringBuilder();
        if (frame != null) {
            sb.append("【活动查询状态】（程序维护的结构化条件，可信，优先于下方文字历史）\n");
            sb.append("frame 请求时间=").append(frame.requestTime())
                    .append("，时区=").append(frame.timezone() == null ? "未携带" : frame.timezone()).append('\n');
            for (EffectiveTarget target : frame.targets()) {
                sb.append(renderTarget(target)).append('\n');
            }
            // 当前焦点（R12）：事实候选里焦点排最前，聚合/比较指代优先绑定
            sb.append(renderFocus(frame));
            if (frame.pendingClarification() != null && !frame.pendingClarification().isNull()) {
                sb.append("【待补条件】上一轮未完成的澄清：").append(frame.pendingClarification()).append('\n');
            }
        }
        // 待补任务独立于 frame 渲染（R1）：新会话首轮澄清没有 frame 也要能接续原任务
        if (pendingTask != null) {
            sb.append("【待补条件】上一轮有未完成的澄清，原问题：「").append(pendingTask.path("question").asText())
                    .append("」，等待用户补充：").append(pendingTask.path("clarify").asText())
                    .append("。若用户本轮在补充该条件，请基于原问题完成原任务，不要当作全新问题。\n");
        }
        // 失败尝试独立于 frame 渲染（R2）：重试/换条件延续失败任务时以此为基准，不退回更早话题
        if (lastAttempt != null) {
            sb.append("【最近失败尝试】问题：「").append(lastAttempt.path("question").asText())
                    .append("」执行失败。用户表达\"再试一次\"或在该问题上换条件延续时，以它为基准生成计划，")
                    .append("不要接续更早的话题。\n");
        }
        // 可引用事实段先于文本历史：预算不足时优先裁文本历史，事实索引完整保留
        sb.append(com.cexpilot.evidence.EvidenceAssembler.render(evidence));
        // 任务索引（R3 一期）：可回访的历史任务，RESUME 的选择依据
        if (tasks != null && !tasks.isEmpty()) {
            sb.append(renderTaskIndex(tasks));
        }
        if (!textHistory.isBlank()) {
            sb.append("【近期问答】（仅辅助理解表达，不作为数据来源）\n").append(textHistory);
        }
        return sb.toString();
    }

    /** 当前焦点段：上一轮展示结果对应的 factId（R12）。无焦点时返回空串。 */
    private static String renderFocus(QueryFrame frame) {
        if (frame.focus() == null) {
            return "";
        }
        StringBuilder s = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonNode node : frame.focus().path("evidence_nodes")) {
            String factId = node.path("fact_id").asText(null);
            if (factId == null || factId.isBlank()) {
                continue;
            }
            if (s.isEmpty()) {
                s.append("【当前焦点】上一轮展示的结果事实：");
            } else {
                s.append("；");
            }
            s.append(factId).append("（").append(node.path("node_id").asText()).append('/')
                    .append(node.path("identity").path("metric").asText()).append('）');
        }
        if (!s.isEmpty()) {
            s.append("。用户说\"这组/刚才那个结果/那个样本集\"时优先绑定焦点事实。\n");
        }
        return s.toString();
    }

    /** 近期任务索引段（R3 一期）：RESUME 回访的选择依据，任务主题摘要用于选择，执行条件以快照为准。 */
    private static String renderTaskIndex(List<TaskSnapshot> tasks) {
        StringBuilder sb = new StringBuilder("【近期任务索引】（用户要回到之前某个任务时，输出 task_action=\"RESUME\"")
                .append("并把 task_ref 设为对应轮次 ID；索引中找不到所指任务时必须澄清，不得拿当前活动任务顶替）\n");
        for (TaskSnapshot task : tasks) {
            sb.append("- 轮次 ").append(task.turnId()).append("（第 ").append(task.queryNo()).append(" 轮）")
                    .append("问题「").append(task.question()).append("」条件：");
            for (EffectiveTarget target : task.frame().targets()) {
                sb.append(renderTarget(target)).append(" / ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 单节点有效条件：metric/形态/完整 instrument/时间/粒度/count/depth 及各自来源标记。 */
    private static String renderTarget(EffectiveTarget t) {
        StringBuilder s = new StringBuilder();
        s.append("- [").append(t.groupId()).append('/').append(t.exchange()).append(']')
                .append(" metric=").append(t.metric())
                .append("，形态=").append(t.queryShape())
                .append("，instrument=").append(t.instrument())
                .append("（settle 来源 ").append(t.settleOrigin()).append(')');
        if (t.timeExpression() != null) {
            s.append("，时间=").append(t.timeExpression());
        }
        if (t.intervalOrigin() != null) {
            s.append("，粒度=").append(t.interval() == null ? "自动选择" : t.interval())
                    .append('(').append(t.intervalOrigin()).append(')');
        }
        if (t.includeUnclosedOrigin() != null) {
            s.append("，include_unclosed=").append(t.includeUnclosed())
                    .append('(').append(t.includeUnclosedOrigin()).append(')');
        }
        if (t.count() != null) {
            s.append("，count=").append(t.count());
        }
        if (t.depth() != null) {
            s.append("，depth=").append(t.depth()).append('(').append(t.depthOrigin()).append(')');
        }
        s.append("，单位=").append(t.unit()).append("，provider=").append(t.provider());
        return s.toString();
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
