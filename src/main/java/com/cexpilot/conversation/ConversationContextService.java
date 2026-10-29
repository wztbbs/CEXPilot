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
    // 可引用事实候选上限（审查 4.4）：焦点事实排最前后截断，控制 prompt 预算
    private static final int EVIDENCE_CANDIDATE_LIMIT = 10;
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
        TaskState current = repository.findActiveTask(conversationId);
        QueryFrame frame = current == null ? loadActiveFrame(conversationId) : current.frame();
        List<TaskSnapshot> tasks = loadTasks(conversationId, current);
        java.util.Set<String> prioritized = new java.util.LinkedHashSet<>(focusFactIds(frame));
        for (TaskSnapshot task : tasks) prioritized.addAll(focusFactIds(task.frame()));
        List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence = loadEvidence(conversationId, prioritized);
        TextHistory history = renderTextHistory(conversationId);
        var pendingTask = current == null ? loadStateJson(repository.findPendingTaskJson(conversationId), "pending_task") : null;
        var lastAttempt = current == null ? loadStateJson(repository.findLastAttemptJson(conversationId), "last_attempt") : null;
        String context = render(frame, history.text(), evidence, pendingTask, lastAttempt, tasks);
        if (current != null) context = renderCurrentTask(current, evidence) + context;
        return new PlannerInput(context, frame, evidence, history.count(), tasks,
                current != null ? current : frame == null ? null : TaskState.legacy(frame, 0, frame.originalQuestion()));
    }

    /** 索引按任务取近期任务并保留最初任务；每项提供最新、最近版本和最初版本。 */
    private List<TaskSnapshot> loadTasks(String conversationId, TaskState current) {
        var byId = new java.util.LinkedHashMap<String, TaskState>();
        if (current != null) byId.put(current.frameId(), current);
        for (TaskState state : repository.recentTasks(conversationId, TASK_INDEX_LIMIT)) {
            byId.putIfAbsent(state.frameId(), state);
        }
        TaskState first = repository.firstTask(conversationId);
        if (first != null) byId.putIfAbsent(first.frameId(), first);
        if (byId.isEmpty()) {
            // 旧数据过渡：没有独立任务时仍可使用已存在的轮次快照。
            List<TaskSnapshot> legacy = repository.recentFrames(conversationId, TASK_INDEX_LIMIT);
            return legacy == null ? List.of() : legacy;
        }
        List<TaskSnapshot> result = new java.util.ArrayList<>();
        int index = 0;
        for (TaskState latest : byId.values()) {
            String ref = "task_" + (++index);
            var versions = new java.util.TreeMap<Long, TaskState>();
            versions.put(latest.version(), latest);
            for (TaskState state : repository.recentTaskVersions(conversationId, latest.frameId(), 2)) {
                versions.putIfAbsent(state.version(), state);
            }
            TaskState initial = repository.firstTaskVersion(conversationId, latest.frameId());
            if (initial != null) versions.putIfAbsent(initial.version(), initial);
            if (initial != null && initial.frame() == null) {
                TaskState executable = repository.firstExecutableTaskVersion(conversationId, latest.frameId());
                if (executable != null) versions.putIfAbsent(executable.version(), executable);
            }
            if (latest.successfulFrame() != null && !versions.containsKey(latest.successfulFrame().version())) {
                TaskState success = repository.taskVersion(conversationId, latest.frameId(), latest.successfulFrame().version());
                if (success != null) versions.putIfAbsent(success.version(), success);
            }
            for (TaskState state : versions.values()) result.add(TaskSnapshot.candidate(state, ref));
        }
        return List.copyOf(result);
    }

    private static String renderCurrentTask(TaskState state,
            List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence) {
        StringBuilder out = new StringBuilder("【当前任务】frame_id=").append(state.frameId())
                .append("，version=").append(state.version()).append("，status=").append(state.status())
                .append("，请求时间=").append(state.requestTime()).append("，时区=").append(state.timezone())
                .append("，原始问题：「").append(state.originQuestion()).append("」\n");
        out.append(ClarificationAnswer.render(state.clarificationAnswers()));
        if (state.logicalPlan() != null && (state.frame() == null || !state.dialogue().isEmpty())) {
            out.append("已保存有效计划（按本轮补充继续规划）：").append(storedPlanForContext(state, evidence)).append('\n');
        }
        if (!state.dialogue().isEmpty()) {
            out.append("【未完成任务接续】下列补充均属于该任务，不用最后一句覆盖原问题。\n");
            for (TaskState.Dialogue step : state.dialogue()) {
                out.append("用户：").append(step.question()).append('\n');
                if (step.response() != null) out.append("系统：").append(step.response()).append('\n');
            }
            out.append("补充条件或再试一次时延续该任务；明确换话题用 NEW。\n");
        }
        return out.toString();
    }

    /** 存储计划中的 evidence_ref 是旧轮别名，展示时按持久化焦点重新映射到本轮候选。 */
    private static com.fasterxml.jackson.databind.JsonNode storedPlanForContext(TaskState state,
            List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence) {
        var copy = state.logicalPlan().deepCopy();
        var byFact = new java.util.HashMap<String, String>();
        for (var entry : evidence) byFact.put(entry.fact().factId(), entry.ref());
        for (var metric : copy.path("metrics")) {
            if (!"pinned_result".equals(metric.path("reference_mode").asText()) || !metric.isObject()) continue;
            String ref = null;
            if (state.frame() != null && state.frame().focus() != null) {
                for (var target : state.frame().targets()) {
                    if (!target.groupId().equals(metric.path("id").asText())) continue;
                    for (var node : state.frame().focus().path("evidence_nodes")) {
                        if (target.physicalId().equals(node.path("node_id").asText())) {
                            ref = byFact.get(node.path("fact_id").asText());
                        }
                    }
                }
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) metric).put("evidence_ref", ref == null ? "不可用" : ref);
        }
        return copy;
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
            List<com.cexpilot.evidence.EvidenceAssembler.Entry> entries =
                    com.cexpilot.evidence.EvidenceAssembler.assignRefs(
                            evidenceStore.listReusable(conversationId, java.time.Instant.now()), focusFactIds);
            // 候选预算（4.4）：焦点事实已在最前，超出上限截断尾部的旧候选
            return entries.size() > EVIDENCE_CANDIDATE_LIMIT
                    ? entries.subList(0, EVIDENCE_CANDIDATE_LIMIT) : entries;
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
            sb.append(renderFocus(frame, evidence, true));
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
            sb.append(renderTaskIndex(tasks, evidence));
        }
        if (!textHistory.isBlank()) {
            sb.append("【近期问答】（仅辅助理解表达，不作为数据来源）\n").append(textHistory);
        }
        return sb.toString();
    }

    /** 焦点使用本轮同一份候选映射，直接给出可输出的 ref；过滤/过期的焦点明确标成不可用。 */
    private static String renderFocus(QueryFrame frame,
                                     List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence, boolean active) {
        if (frame.focus() == null) {
            return "";
        }
        var byFactId = new java.util.HashMap<String, com.cexpilot.evidence.EvidenceAssembler.Entry>();
        for (var entry : evidence) {
            byFactId.put(entry.fact().factId(), entry);
        }
        StringBuilder s = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonNode node : frame.focus().path("evidence_nodes")) {
            String factId = node.path("fact_id").asText(null);
            if (factId == null || factId.isBlank()) {
                continue;
            }
            if (s.isEmpty()) {
                s.append(active ? "【当前焦点】该状态展示的结果事实：\n" : "【该历史任务焦点】该状态展示的结果事实：\n");
            }
            s.append("- node=").append(node.path("node_id").asText())
                    .append("，metric=").append(node.path("identity").path("metric").asText());
            var entry = byFactId.get(factId);
            if (entry == null) {
                s.append("，evidence_ref=不可用（原事实不在本轮候选中，不能编造引用或用最新值冒充）");
            } else {
                s.append("，evidence_ref=").append(entry.ref())
                        .append("，source_turn_id=").append(entry.fact().sourceTurnId());
                s.append(entry.fact().derived()
                        ? "（派生结果，不可绑定为原始取数事实）"
                        : "（原始事实，可按此 ref 绑定）");
            }
            s.append('\n');
        }
        if (!s.isEmpty()) {
            s.append("用户引用该状态的这组或该结果时，使用上述可绑定 ref；有多个合理目标时澄清。\n");
        }
        return s.toString();
    }

    /** 近期任务索引段（R3 一期）：RESUME 回访的选择依据，任务主题摘要用于选择，执行条件以快照为准。 */
    private static String renderTaskIndex(List<TaskSnapshot> tasks,
            List<com.cexpilot.evidence.EvidenceAssembler.Entry> evidence) {
        StringBuilder sb = new StringBuilder("【近期任务索引】按任务及版本选择。RESUME 指定 task_ref 和 state_ref；")
                .append("回到最开始的查询选择 first_query_no 最小任务的原查询版本；最初曾澄清时可选择首个有效条件版本。明确第几轮则选择对应轮次版本。回到某任务继续可选择最新版本。")
                .append("无法唯一定位时澄清。\n");
        for (TaskSnapshot task : tasks) {
            sb.append("- task_ref=").append(task.taskRef()).append("，state_ref=").append(task.stateRef())
                    .append("，frame_id=").append(task.state().frameId()).append("，version=").append(task.state().version())
                    .append("，first_query_no=").append(task.state().firstQueryNo())
                    .append("，轮次 ").append(task.turnId()).append("（第 ").append(task.queryNo()).append(" 轮）")
                    .append("，status=").append(task.state().status()).append("，请求时间=").append(task.state().requestTime())
                    .append("，时区=").append(task.state().timezone()).append("，问题「").append(task.question()).append("」\n");
            if (task.frame() != null) {
                sb.append("请求时间=").append(task.frame().requestTime()).append("，时区=")
                        .append(task.frame().timezone()).append('\n');
                for (EffectiveTarget target : task.frame().targets()) sb.append(renderTarget(target)).append('\n');
                sb.append(renderFocus(task.frame(), evidence, false));
                if (task.state().logicalPlan() != null) {
                    var plan = storedPlanForContext(task.state(), evidence);
                    boolean pinned = false;
                    for (var metric : plan.path("metrics")) pinned |= "pinned_result".equals(metric.path("reference_mode").asText());
                    if (pinned || !plan.path("calculations").isEmpty()) sb.append("完整计划=").append(plan).append('\n');
                }
            } else if (task.state().logicalPlan() != null) {
                sb.append("有效计划=").append(storedPlanForContext(task.state(), evidence)).append('\n');
            }
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
        if (!t.conditionSources().isEmpty()) {
            s.append("，条件来源=").append(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(t.conditionSources()).toString());
        }
        if (t.executionMetadata() != null) {
            s.append("，上次执行元数据（只说明上次结果，不是本次固定要求）=").append(t.executionMetadata());
        }
        return s.toString();
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
