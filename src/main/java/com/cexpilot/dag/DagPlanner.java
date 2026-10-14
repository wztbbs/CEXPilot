package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.evidence.EvidenceBindingContext;
import com.cexpilot.metric.MetricCatalog;
import com.cexpilot.conversation.TaskState;
import com.cexpilot.conversation.TaskScope;
import com.cexpilot.metric.MetricPlanCompiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.LlmJson;
import com.cexpilot.llm.LlmTraceSerializer;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 单次 LLM 生成紧凑指标计划；确定性编译为 Tool DAG，再校验和有限次数修复。 */
@Component
public class DagPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROMPT_NAME = "dag_planner";
    private static final String TRACE_NAME = "dag_planner";
    /** 透传 reply 的长度上限：合法追问/边界话术都很短，超长基本是把推理过程倒进了 reply。 */
    private static final int REPLY_MAX_LENGTH = 100;
    /** 模型举了澄清旗但没写 clarify/reply 文案时的兜底话术（不得编造具体歧义内容）。 */
    private static final String CLARIFY_FALLBACK_TEXT = "这个问题我需要再确认一下，请补充说明您想查询的对象或条件。";

    private final LlmClient llm;
    private final LlmClient repairLlm;
    private final ToolRegistry registry;
    private final LlmConfig llmConfig;
    private final DagConfig dagConfig;
    private final PromptStore prompts;
    private final PlanValidator validator;
    private final MetricCatalog catalog;
    private final MetricPlanCompiler compiler;
    private final InheritanceValidator inheritanceValidator = new InheritanceValidator();

    public DagPlanner(LlmClient llm, ToolRegistry registry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator, com.cexpilot.metric.MetricProviderRegistry metricProviders) {
        this(llm, registry, llmConfig, dagConfig, prompts, validator,
                new MetricCatalog(new DefaultResourceLoader()), metricProviders);
    }

    public DagPlanner(LlmClient llm, ToolRegistry registry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator, MetricCatalog catalog,
                      com.cexpilot.metric.MetricProviderRegistry metricProviders) {
        // 兼容测试/显式单客户端调用；生产由下面的双客户端构造注入。
        this(llm, llm, registry, llmConfig, dagConfig, prompts, validator, catalog, metricProviders);
    }

    @Autowired
    public DagPlanner(
            @org.springframework.beans.factory.annotation.Qualifier(com.cexpilot.config.LlmClientConfig.NORMAL_LLM_CLIENT) LlmClient llm,
            @org.springframework.beans.factory.annotation.Qualifier(com.cexpilot.config.LlmClientConfig.FLAGSHIP_LLM_CLIENT) LlmClient repairLlm,
            ToolRegistry registry, LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
            PlanValidator validator, MetricCatalog catalog,
            com.cexpilot.metric.MetricProviderRegistry metricProviders) {
        this.catalog = catalog;
        this.compiler = new MetricPlanCompiler(catalog, registry, metricProviders);
        this.llm = llm;
        this.repairLlm = repairLlm;
        this.registry = registry;
        this.llmConfig = llmConfig;
        this.dagConfig = dagConfig;
        this.prompts = prompts;
        this.validator = validator;
    }

    /**
     * 规划结果。inDomain=false 时 reply 为边界话术；inDomain=true 且 plan 为空表示
     * 模型判断工具不足以回答（reply 说明缺口）或 repair 耗尽（lastError 非空）。
     * plan 持有完整编译产物 CompiledPlan（含 LogicalPlan 与逻辑→物理映射），执行用其中的 dagPlan。
     * needsClarification=true 时不执行 DAG，编排层在所选任务上追加澄清版本。
     * bindings 为服务端生成的执行期绑定（阶段二 REUSE_FACT）；无证据上下文或未绑定时为 null。
     * llmCalls 为本轮 planner 阶段真实 LLM 调用次数（含修复重试，阶段三计数拆分）。
     */
    public record PlanOutcome(boolean inDomain, String reply,
                              Optional<com.cexpilot.metric.CompiledPlan> plan, int promptTokens, int completionTokens,
                              String lastError, boolean needsClarification,
                              com.cexpilot.metric.ExecutionBindings bindings, int llmCalls, TaskScope taskScope,
                              List<LlmUsage> llmUsages) {
        public PlanOutcome {
            llmUsages = llmUsages == null ? List.of() : List.copyOf(llmUsages);
        }
        public PlanOutcome(boolean inDomain, String reply, Optional<com.cexpilot.metric.CompiledPlan> plan,
                int promptTokens, int completionTokens, String lastError, boolean needsClarification,
                com.cexpilot.metric.ExecutionBindings bindings, int llmCalls, TaskScope taskScope) {
            this(inDomain, reply, plan, promptTokens, completionTokens, lastError, needsClarification,
                    bindings, llmCalls, taskScope, List.of());
        }
        public PlanOutcome(boolean inDomain, String reply, Optional<com.cexpilot.metric.CompiledPlan> plan,
                int promptTokens, int completionTokens, String lastError, boolean needsClarification,
                com.cexpilot.metric.ExecutionBindings bindings, int llmCalls) {
            this(inDomain, reply, plan, promptTokens, completionTokens, lastError, needsClarification,
                    bindings, llmCalls, null);
        }
    }

    public PlanOutcome plan(String question, String conversationContext, String traceId, TraceSink sink) {
        return plan(question, conversationContext, traceId, sink, null, null);
    }

    /**
     * @param activeFrame    活动 QueryFrame（阶段一 c）；非 null 时与 Planner 声明的 changes/new_frame 做继承对账
     * @param requestContext 请求时间上下文；时间条件按消解后的绝对区间对账，需要本请求的基准时间与时区
     */
    public PlanOutcome plan(String question, String conversationContext, String traceId, TraceSink sink,
                            com.cexpilot.conversation.QueryFrame activeFrame,
                            com.cexpilot.runtime.RequestContext requestContext) {
        return plan(question, conversationContext, traceId, sink, activeFrame, requestContext, null, List.of());
    }

    public PlanOutcome plan(String question, String conversationContext, String traceId, TraceSink sink,
                            com.cexpilot.conversation.QueryFrame activeFrame,
                            com.cexpilot.runtime.RequestContext requestContext,
                            EvidenceBindingContext evidenceContext) {
        return plan(question, conversationContext, traceId, sink, activeFrame, requestContext,
                evidenceContext, List.of());
    }

    /**
     * @param evidenceContext 历史事实绑定上下文（阶段二）：非 null 时校验 pinned_result 的 evidence_ref
     *                        并把通过校验的 ExecutionBindings 挂到 PlanOutcome
     * @param taskIndex       可回访任务/版本候选：RESUME 时按 task_ref/state_ref 定位
     *                        历史状态，服务端校验候选归属；新协议用候选别名，旧轮次引用仅作兼容
     */
    public PlanOutcome plan(String question, String conversationContext, String traceId, TraceSink sink,
                            com.cexpilot.conversation.QueryFrame activeFrame,
                            com.cexpilot.runtime.RequestContext requestContext,
                            EvidenceBindingContext evidenceContext,
                            List<com.cexpilot.conversation.TaskSnapshot> taskIndex) {
        return plan(question, conversationContext, traceId, sink, activeFrame, requestContext, evidenceContext,
                taskIndex, activeFrame == null ? null : TaskState.legacy(activeFrame, 0, activeFrame.originalQuestion()));
    }

    public PlanOutcome plan(String question, String conversationContext, String traceId, TraceSink sink,
                            com.cexpilot.conversation.QueryFrame activeFrame,
                            com.cexpilot.runtime.RequestContext requestContext, EvidenceBindingContext evidenceContext,
                            List<com.cexpilot.conversation.TaskSnapshot> taskIndex, TaskState currentTask) {
        TaskScope lastScope = TaskScope.current(currentTask);
        int maxToolCalls = llmConfig.getMaxToolCalls();
        String systemPrompt = systemPrompt(conversationContext);
        JsonNode responseFormat = plannerResponseFormat();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(question));

        int promptTokens = 0;
        int completionTokens = 0;
        int llmCalls = 0;
        List<LlmUsage> usages = new ArrayList<>();
        String lastError = null;
        boolean bindingRetried = false;
        // R4：首次绑定失败后，逐项保留历史输入的条件及计算用途；修复改名不影响约束。
        PinnedRequirements pinnedRequirements = new PinnedRequirements();
        int attempts = dagConfig.getPlannerMaxRetries() + 1;
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                // 4.3 口径：按尝试次数计——传输失败的调用（欠费/超时）也是已发生的成本
                llmCalls++;
                ChatResponse response;
                try {
                    response = callLlm(messages, traceId, sink, attempt, responseFormat, usages);
                } catch (Exception e) {
                    recordAttempt(traceId, sink, attempt, lastError, "FAILED", "NOT_RUN", "CALL_FAILED",
                            e.getMessage(), null);
                    throw e;
                }
                promptTokens += response.promptTokens() == null ? 0 : response.promptTokens();
                completionTokens += response.completionTokens() == null ? 0 : response.completionTokens();

                PlanDecision decision = evaluateResponse(response.content(), maxToolCalls, traceId, sink,
                        activeFrame, requestContext, evidenceContext, attempt, pinnedRequirements, taskIndex, currentTask, question);
                if (decision.taskScope() != null) lastScope = decision.taskScope();
                if (decision.error() == null) {
                    if (!pinnedRequirements.isEmpty() && decision.plan() != null
                            && !pinnedRequirements.preservedBy(decision.plan())) {
                        sink.record(new TraceEvent(traceId, "PLAN", "dag_planner", null,
                                MAPPER.createObjectNode().put("note", "pinned_downgrade_blocked").toString(),
                                null, null, null, null, null, "修复轮改变或删除了必要的历史输入，按原快照不可恢复处理"));
                        recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED", "NEEDS_CLARIFICATION",
                                "修复轮改变或删除了必要的历史输入，按原快照不可恢复处理", null);
                        return new PlanOutcome(true, BINDING_UNAVAILABLE_CLARIFY, Optional.empty(),
                                promptTokens, completionTokens, null, true, null, llmCalls, lastScope, usages);
                    }
                    String result = decision.plan() != null ? "EXECUTABLE"
                            : decision.needsClarification() ? "NEEDS_CLARIFICATION"
                            : decision.inDomain() ? "NO_PLAN" : "OUT_OF_DOMAIN";
                    recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED",
                            decision.plan() == null ? "NOT_APPLICABLE" : "PASSED", result, null, decision.plan());
                    return decision.toOutcome(promptTokens, completionTokens, llmCalls, usages);
                }
                if (decision.bindingFailure()) {
                    // 绑定失败只修复一次；仍不行则澄清"原快照不可恢复"，禁止重查当前值冒充（F21）
                    if (bindingRetried) {
                        recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED", "NEEDS_CLARIFICATION",
                                decision.error(), null);
                        return new PlanOutcome(true, BINDING_UNAVAILABLE_CLARIFY, Optional.empty(),
                                promptTokens, completionTokens, null, true, null, llmCalls, lastScope, usages);
                    }
                    bindingRetried = true;
                }
                recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED",
                        attempt == attempts ? "REPAIR_EXHAUSTED" : "REPAIR_REQUIRED",
                        decision.error(), null);
                lastError = decision.error();
                appendRepair(messages, response.content(), lastError);
            }
        } catch (Exception e) {
            // 4.3：planner 阶段失败（LLM 欠费/超时等）也要把已发生的 LLM 调用次数带出——
            // planner 是最常见的失败源，裸抛会让失败 trace 的计数归零
            throw new PlanningFailure(e, llmCalls, lastScope, usages);
        }
        // repair 耗尽：runtime 直接返回固定失败话术，禁止继续生成无事实答案。
        return new PlanOutcome(true, null,
                Optional.empty(), promptTokens, completionTokens, lastError, false, null, llmCalls, lastScope, usages);
    }

    /** planner 阶段失败（LLM 传输错误等）携带已发生的调用次数（4.3），由 DagRuntime 转成 ExecutionFailure。 */
    public static final class PlanningFailure extends RuntimeException {

        public final int llmCalls;
        public final TaskScope taskScope;
        public final List<LlmUsage> llmUsages;

        public PlanningFailure(Throwable cause, int llmCalls) { this(cause, llmCalls, null); }
        public PlanningFailure(Throwable cause, int llmCalls, TaskScope taskScope) {
            this(cause, llmCalls, taskScope, List.of());
        }
        public PlanningFailure(Throwable cause, int llmCalls, TaskScope taskScope, List<LlmUsage> usages) {
            super(cause.getMessage(), cause);
            this.llmCalls = llmCalls;
            this.taskScope = taskScope;
            this.llmUsages = List.copyOf(usages);
        }
    }

    /** 绑定修复仍失败时的澄清话术：说明原快照不可恢复，把"是否按最新数据重查"的选择权交还用户。 */
    static final String BINDING_UNAVAILABLE_CLARIFY =
            "您想引用的那条历史数据快照已不可恢复（可能已过期、被清理或查询口径已变化），无法按原样复用；"
                    + "请确认是否按当前最新数据重新查询。";

    /** 单轮判断不负责 token 累计；error 非空时由 plan 统一追加修复消息并重试。 */
    private record PlanDecision(boolean inDomain, String reply, com.cexpilot.metric.CompiledPlan plan,
                                String error, boolean needsClarification,
                                com.cexpilot.metric.ExecutionBindings bindings, boolean bindingFailure, TaskScope taskScope) {
        private static PlanDecision accepted(boolean inDomain, String reply, com.cexpilot.metric.CompiledPlan plan) {
            return new PlanDecision(inDomain, reply, plan, null, false, null, false, null);
        }

        private static PlanDecision accepted(boolean inDomain, String reply, com.cexpilot.metric.CompiledPlan plan,
                                             com.cexpilot.metric.ExecutionBindings bindings) {
            return new PlanDecision(inDomain, reply, plan, null, false, bindings, false, null);
        }

        private static PlanDecision clarification(String reply) {
            return new PlanDecision(true, reply, null, null, true, null, false, null);
        }

        private static PlanDecision bindingError(String error) {
            return new PlanDecision(true, null, null, error, false, null, true, null);
        }

        private PlanDecision withScope(TaskScope scope) {
            return new PlanDecision(inDomain, reply, plan, error, needsClarification, bindings, bindingFailure, scope);
        }

        private PlanOutcome toOutcome(int promptTokens, int completionTokens, int llmCalls, List<LlmUsage> usages) {
            return new PlanOutcome(inDomain, reply, Optional.ofNullable(plan),
                    promptTokens, completionTokens, error, needsClarification, bindings, llmCalls, taskScope, usages);
        }
    }

    private PlanDecision evaluateResponse(String content, int maxToolCalls,
                                          String traceId, TraceSink sink,
                                          com.cexpilot.conversation.QueryFrame activeFrame,
                                          com.cexpilot.runtime.RequestContext requestContext,
                                          EvidenceBindingContext evidenceContext,
                                          int attempt, PinnedRequirements pinnedRequirements,
                                          List<com.cexpilot.conversation.TaskSnapshot> taskIndex, TaskState currentTask, String question) {
        JsonNode parsed;
        try {
            parsed = LlmJson.parse(content);
        } catch (Exception e) {
            return repairDecision(traceId, sink, planOutput(content), "输出 JSON 解析失败: " + e.getMessage());
        }

        if (!parsed.isObject() || !parsed.path("in_domain").isBoolean()) {
            String error = "输出缺少信封：必须输出完整 JSON 对象 {\"in_domain\", \"reply\", \"plan\"}，"
                    + "不要直接输出指标数组或旧版 nodes";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        if (!parsed.path("in_domain").asBoolean(false)) {
            sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
            return PlanDecision.accepted(false, textOrNull(parsed.path("reply")), null);
        }
        return evaluateInDomainResponse(parsed, maxToolCalls, traceId, sink, activeFrame, requestContext,
                evidenceContext, attempt, pinnedRequirements, taskIndex, currentTask, question);
    }

    private PlanDecision evaluateInDomainResponse(JsonNode parsed, int maxToolCalls,
                                                  String traceId, TraceSink sink,
                                                  com.cexpilot.conversation.QueryFrame activeFrame,
                                                  com.cexpilot.runtime.RequestContext requestContext,
                                                  EvidenceBindingContext evidenceContext,
                                                  int attempt, PinnedRequirements pinnedRequirements,
                                                  List<com.cexpilot.conversation.TaskSnapshot> taskIndex, TaskState currentTask, String question) {
        TaskScope selected;
        try { selected = selectTask(parsed, currentTask, taskIndex); }
        catch (IllegalArgumentException e) {
            return PlanDecision.clarification(e.getMessage()).withScope(
                    currentTask != null && !currentTask.dialogue().isEmpty()
                            ? TaskScope.current(currentTask) : new TaskScope(TaskScope.Action.NEW, null));
        }
        String reply = textOrNull(parsed.path("reply"));
        // 澄清决策（阶段一 c 协议增量字段，缺省按旧行为 false）：不执行、不落 frame，由程序落 NEEDS_CLARIFICATION
        if (parsed.path("needs_clarification").asBoolean(false)) {
            String clarify = textOrNull(parsed.path("clarify"));
            String text = clarify != null && !clarify.isBlank() ? clarify : reply;
            if (text == null || text.isBlank()) {
                if (attempt <= 1) {
                    return repairDecision(traceId, sink, parsed.toString(),
                            "needs_clarification=true 时必须在 clarify 写明要问用户的一句澄清问题");
                }
                // 模型举了澄清旗但文案字段为空（线上实测 qwen-plus 修复后仍逐字重发）：
                // 澄清决策本身有效，用通用话术兜底并记审计事件，不因文案缺失丢掉澄清轮
                sink.record(new TraceEvent(traceId, "CLARIFY_FALLBACK", "dag_planner",
                        parsed.toString(), null, null, null, null, null, null, null));
                return PlanDecision.clarification(CLARIFY_FALLBACK_TEXT).withScope(selected);
            }
            sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
            return PlanDecision.clarification(text).withScope(selected);
        }
        JsonNode planNode = parsed.path("plan");
        if (!planNode.isObject()) {
            return evaluateReplyWithoutPlan(parsed, reply, traceId, sink).withScope(selected);
        }
        var context = requestContext == null ? null : selected.executionContext(requestContext);
        return validatePlan(parsed, reply, maxToolCalls, traceId, sink, selected.frame(), context,
                evidenceContext, attempt, pinnedRequirements, question).withScope(selected);
    }

    private static TaskScope selectTask(JsonNode parsed, TaskState current,
            List<com.cexpilot.conversation.TaskSnapshot> candidates) {
        String action = textOrNull(parsed.path("task_action"));
        if (action == null) action = parsed.path("new_frame").asBoolean(false) ? "NEW" : "CONTINUE";
        TaskScope.Action type;
        try { type = TaskScope.Action.valueOf(action.toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("请确认您要继续当前任务、开启新任务，还是回到哪个历史任务。"); }
        String ref = textOrNull(parsed.path("task_ref"));
        String stateRef = textOrNull(parsed.path("state_ref"));
        if (type == TaskScope.Action.NEW) {
            if (ref != null || stateRef != null) throw new IllegalArgumentException("新任务不能同时引用历史任务，请明确要查询的新问题。");
            return new TaskScope(type, null);
        }
        if (type == TaskScope.Action.CONTINUE) {
            if (stateRef != null) throw new IllegalArgumentException("回到历史版本请使用 RESUME，并选择对应 state_ref。");
            if (ref != null && (current == null || candidates.stream().noneMatch(t -> t.taskRef().equals(ref)
                    && t.state().frameId().equals(current.frameId())))) {
                throw new IllegalArgumentException("您指定的不是当前任务，请确认要回到哪个历史任务。");
            }
            return TaskScope.current(current);
        }
        var matched = candidates.stream().filter(t -> ref != null && (t.taskRef().equals(ref) || t.turnId().equals(ref)))
                .filter(t -> stateRef == null || t.stateRef().equals(stateRef))
                .max(java.util.Comparator.comparingLong(t -> t.state().version())).orElse(null);
        if (matched == null) throw new IllegalArgumentException("无法定位 task_ref/state_ref 所指的历史任务或版本，请说明要回到哪个问题。");
        return new TaskScope(type, matched.state());
    }

    private PlanDecision evaluateReplyWithoutPlan(JsonNode parsed, String reply,
                                                  String traceId, TraceSink sink) {
        if (reply == null || reply.isBlank()) {
            // plan 缺失且无任何话术 = 协议违约，必须 repair。
            String error = "缺少 plan 且未给出 reply：可查询时必须输出 plan.metrics 和 plan.calculations；"
                    + "确需拒绝时必须在 reply 写明原因";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        if (isReasoningDump(reply)) {
            // 没有 plan 时，超长 reply 按协议错误修复，不能把推理原文透传成答案。
            String error = "reply 只能写一句要对用户说的简短话术（" + REPLY_MAX_LENGTH
                    + " 字以内），禁止输出推理过程；问题可查询时必须输出 plan 字段";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        // plan=null 或空 metrics/calculations + 非空 reply：模型有意不规划，直接接受不 repair。
        sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
        return PlanDecision.accepted(true, reply, null);
    }

    private PlanDecision validatePlan(JsonNode parsed, String reply, int maxToolCalls,
                                      String traceId, TraceSink sink,
                                      com.cexpilot.conversation.QueryFrame activeFrame,
                                      com.cexpilot.runtime.RequestContext requestContext,
                                      EvidenceBindingContext evidenceContext,
                                      int attempt, PinnedRequirements pinnedRequirements, String question) {
        String error;
        try {
            com.cexpilot.metric.CompiledPlan compiled =
                    new ConditionProvenanceResolver().resolve(compiler, parsed.path("plan"),
                            parsed.path("explicit_conditions"), question, activeFrame, requestContext,
                            Math.min(dagConfig.getMaxNodes(), maxToolCalls));
            DagPlan plan = compiled.dagPlan();
            if (plan.nodes().isEmpty() && reply != null && !reply.isBlank()) {
                return evaluateReplyWithoutPlan(parsed, reply, traceId, sink);
            }
            List<String> errors = validator.validate(plan, compiler.allowedTools(), maxToolCalls);
            if (errors.isEmpty()) {
                // 继承对账：NEW 无基准，CONTINUE/RESUME 使用已选择的任务版本。漏报 changes 的第一次先修复教学；修复后仍不声明则
                // shadow 放行——计划本身已通过全部校验，changes 只是审计元数据，不构成拒绝理由——
                // 并记 INHERIT_SHADOW 事件供观察模型漏报率。
                if (activeFrame != null) {
                    String reconciliation = inheritanceValidator.check(activeFrame, compiled,
                            declaredChanges(parsed), requestContext);
                    if (reconciliation != null) {
                        if (attempt <= 1) {
                            return repairDecision(traceId, sink, parsed.toString(), reconciliation);
                        }
                        // §5 结构化：actual_diff（真实差异明细）/ declared_changes / attempt 分开记录，
                        // 漏报率与逐 case 抽样复核才有可统计口径，而不是只靠 detail 文案
                        sink.record(new TraceEvent(traceId, "INHERIT_SHADOW", "inheritance_validator",
                                MAPPER.createObjectNode()
                                        .put("declared_changes", declaredChanges(parsed).toString())
                                        .put("actual_diff", inheritanceValidator
                                                .diffKeys(activeFrame, compiled, requestContext).toString())
                                        .put("attempt", attempt)
                                        .put("detail", reconciliation).toString(),
                                parsed.toString(), null, null, null, null, null, null));
                    }
                }
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                sink.record(new TraceEvent(traceId, "PLAN_COMPILED", "metric_compiler",
                        MAPPER.createObjectNode().put("catalog_version", catalog.version()).toString(),
                        plan.toJson().toString(), null, null, null, null, null, null));
                // 历史事实绑定（阶段二）：evidence_ref 协议校验 + 服务端实体绑定；
                // 失败走绑定修复（只一次），再失败由 plan() 转澄清，禁止重查冒充
                com.cexpilot.metric.ExecutionBindings bindings = null;
                if (evidenceContext != null && requestContext != null) {
                    EvidenceBinder binder = new EvidenceBinder(evidenceContext.store());
                    String protocolError = binder.checkProtocol(compiled.logicalPlan(), evidenceContext.candidates());
                    if (protocolError != null) {
                        pinnedRequirements.capture(compiled);
                        sink.record(TraceEvent.plan(traceId, parsed.toString(), protocolError));
                        return PlanDecision.bindingError(protocolError);
                    }
                    EvidenceBinder.BindResult bind = binder.bind(compiled, evidenceContext.candidates(),
                            evidenceContext.conversationId(), requestContext, traceId, sink);
                    if (bind.error() != null) {
                        pinnedRequirements.capture(compiled);
                        sink.record(TraceEvent.plan(traceId, parsed.toString(), bind.error()));
                        return PlanDecision.bindingError(bind.error());
                    }
                    bindings = bind.bindings();
                    // F05/R5 后置核对：只产出非阻断提示（字面量与历史快照值一致），落审计事件供回放参考；
                    // 比较方向不再程序校验——方向由本轮语义决定，交给 prompt 规则与 Answer 来源标注
                    java.util.List<String> comparisonWarnings = binder.checkComparisonWarnings(compiled, bindings,
                            evidenceContext.candidates());
                    if (!comparisonWarnings.isEmpty()) {
                        var arr = MAPPER.createArrayNode();
                        comparisonWarnings.forEach(arr::add);
                        sink.record(new TraceEvent(traceId, "LITERAL_MATCH_NOTICE", "evidence_binder",
                                null, arr.toString(), null, null, null, null, null, null));
                    }
                }
                // plan 已合法时，超长 reply 直接丢弃，不再为 reply 重试。
                String effectiveReply = isReasoningDump(reply) ? null : reply;
                // FOLLOWUP_PLAN（阶段三观测）：计划校验通过（含绑定生成）后落计划摘要，
                // 只放各节点口径与绑定决策，不放大 payload
                sink.record(new TraceEvent(traceId, "FOLLOWUP_PLAN", "dag_planner",
                        null, followupPlanSummary(parsed, compiled, bindings),
                        null, null, null, null, null, null));
                return PlanDecision.accepted(true, effectiveReply, compiled, bindings);
            }
            error = planValidationError(compiler.logicalErrors(plan, errors));
        } catch (Exception e) {
            error = "plan 解析失败: " + e.getMessage();
        }
        return repairDecision(traceId, sink, parsed.toString(), error);
    }

    /** 协议增量字段 changes：字符串数组，缺省/非法按空数组（旧行为）处理。 */
    private static java.util.Set<String> declaredChanges(JsonNode parsed) {
        java.util.Set<String> changes = new java.util.LinkedHashSet<>();
        JsonNode node = parsed.path("changes");
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    changes.add(item.asText());
                }
            }
        }
        return changes;
    }

    /** FOLLOWUP_PLAN 事件的 output_json：各 metric 节点口径 + 绑定决策（FETCH/REUSE_FACT+factId）+ changes/new_frame 声明。 */
    private static String followupPlanSummary(JsonNode parsed, com.cexpilot.metric.CompiledPlan compiled,
                                              com.cexpilot.metric.ExecutionBindings bindings) {
        ObjectNode out = MAPPER.createObjectNode();
        var metrics = out.putArray("metrics");
        for (com.cexpilot.metric.MetricRequest m : compiled.logicalPlan().metrics()) {
            ObjectNode node = metrics.addObject();
            node.put("id", m.id());
            node.put("metric", m.metric());
            node.put("base", m.instrument() == null ? null : m.instrument().base());
            node.put("shape", String.valueOf(m.shape()));
            node.put("reference_mode", m.referenceMode() == null ? "latest" : m.referenceMode());
            if (m.evidenceRef() != null) {
                node.put("evidence_ref", m.evidenceRef());
            }
            var branches = node.putArray("branches");
            Map<String, String> byExchange = compiled.branches().getOrDefault(m.id(), Map.of());
            byExchange.forEach((exchange, physicalId) -> {
                ObjectNode branch = branches.addObject();
                branch.put("exchange", exchange);
                branch.put("node", physicalId);
                com.cexpilot.metric.ExecutionBindings.ReuseFact reuse =
                        bindings == null ? null : bindings.reuseFor(physicalId);
                branch.put("decision", reuse == null ? "FETCH" : "REUSE_FACT");
                if (reuse != null) {
                    branch.put("fact_id", reuse.factId());
                }
            });
        }
        var calculations = out.putArray("calculations");
        for (com.cexpilot.metric.CalculationRequest c : compiled.logicalPlan().calculations()) {
            calculations.addObject().put("id", c.id()).put("operator", c.operator());
        }
        declaredChanges(parsed).forEach(out.putArray("changes")::add);
        out.put("new_frame", parsed.path("new_frame").asBoolean(false));
        return out.toString();
    }

    private String planValidationError(List<String> errors) {
        return "plan 校验失败: " + String.join("; ", errors)
                + "；仅使用指标目录与算子，引用格式为 {{指标组.交易所.value}} / {{计算ID.value}}，不要输出底层 Tool 节点";
    }

    private PlanDecision repairDecision(String traceId, TraceSink sink, String output, String error) {
        sink.record(TraceEvent.plan(traceId, output, error));
        return new PlanDecision(true, null, null, error, false, null, false, null);
    }

    /** 判断 reply 是否是推理 dump：合法话术很短，超长即视为协议误用。 */
    private static boolean isReasoningDump(String reply) {
        return reply != null && reply.length() > REPLY_MAX_LENGTH;
    }

    /** 按 dag.planner-response-format 构造下发给 planner 调用的 response_format；空配置 = 不下发。 */
    private JsonNode plannerResponseFormat() {
        String format = dagConfig.getPlannerResponseFormat();
        if (format == null || format.isBlank()) {
            return null;
        }
        // 容忍运维层带来的引号（.env / docker env-file 可能不剥引号）
        format = format.trim().replaceAll("^\"+|\"+$", "");
        if ("json_object".equals(format)) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "json_object");
            return node;
        }
        if ("json_schema".equals(format)) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "json_schema");
            ObjectNode jsonSchema = node.putObject("json_schema");
            jsonSchema.put("name", "dag_planner_output");
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", plannerSchema());
            return node;
        }
        throw new IllegalArgumentException(
                "未知的 dag.planner-response-format: " + format + "（支持 json_object / json_schema / 留空）");
    }

    /**
     * planner 输出信封的 JSON Schema。plan/reply 允许 null：模型判断工具不足以回答时
     * 输出 plan=null；metric/operator 字段使用目录枚举，结构为 metrics/calculations。
     * 业务约束及展开后的 DAG 仍由编译器与 PlanValidator 校验。
     */
    private JsonNode plannerSchema() {
        try (var input = new org.springframework.core.io.ClassPathResource("metrics/plan-schema.json").getInputStream()) {
            ObjectNode schema = (ObjectNode) MAPPER.readTree(input);
            ((ObjectNode) schema.at("/properties/plan/properties/metrics/items/properties/metric"))
                    .set("enum", MAPPER.valueToTree(catalog.names()));
            ObjectNode calculations = (ObjectNode) schema.at("/properties/plan/properties/calculations");
            ObjectNode items = MAPPER.createObjectNode();
            var branches = items.putArray("anyOf");
            for (String name : catalog.operators().stream().sorted().toList()) {
                if (!(registry.get(name) instanceof com.cexpilot.calculation.CalculationTool tool)) continue;
                ObjectNode branch = branches.addObject().put("type", "object").put("additionalProperties", false);
                branch.putArray("required").add("id").add("operator").add("input");
                var properties = branch.putObject("properties");
                properties.putObject("id").put("type", "string");
                properties.putObject("operator").put("type", "string").putArray("enum").add(name);
                properties.set("input", tool.planningInputSchema());
            }
            calculations.set("items", items);
            return schema;
        } catch (Exception e) {
            throw new IllegalStateException("加载指标计划 schema 失败", e);
        }
    }

    private String systemPrompt(String conversationContext) {
        return prompts.render(PROMPT_NAME, Map.of(
                "concepts", catalog.concepts(),
                "metrics", catalog.describeMetrics(),
                "operators", catalog.describeOperators(registry),
                "max_nodes", String.valueOf(Math.min(dagConfig.getMaxNodes(), llmConfig.getMaxToolCalls())),
                "max_depth", String.valueOf(dagConfig.getMaxDepth()),
                "conversation_context", conversationContext == null ? "" : conversationContext));
    }

    /** 不含每轮历史的有效规划提示词版本，包含指标目录（含绑定）、算子配置。 */
    public String promptVersion() {
        return PromptStore.fingerprint(systemPrompt("") + catalog.version());
    }

    private void appendRepair(List<ChatMessage> messages, String badOutput, String error) {
        messages.add(ChatMessage.assistant(badOutput, null));
        messages.add(ChatMessage.user("上一次输出不合法，请修正后重新输出完整 JSON，只输出 JSON。错误明细：\n" + error));
    }

    private ChatResponse callLlm(List<ChatMessage> messages, String traceId, TraceSink sink, int attempt,
                                 JsonNode responseFormat, List<LlmUsage> usages) {
        LlmClient client = attempt == 1 ? llm : repairLlm;
        // 单客户端兼容构造不能把同一个真实客户端按模型 2 的单价计费。
        boolean useRepair = attempt > 1 && repairLlm != llm;
        String role = useRepair ? "flagship" : "normal";
        LlmConfig.ModelConfig model = useRepair ? llmConfig.getFlagship() : llmConfig.getNormal();
        long start = System.currentTimeMillis();
        String input = eventInput(attempt, messages, responseFormat, client, role, model.getModel());
        boolean received = false;
        try {
            ChatResponse response = client.chat(messages, null, responseFormat);
            LlmUsage usage = LlmUsage.of(role, model, response);
            usages.add(usage);
            received = true;
            ObjectNode output = LlmTraceSerializer.responseToObject(response);
            output.put("model_role", role).put("model", model.getModel());
            output.set("estimated_cost", MAPPER.valueToTree(usage.estimatedCost()));
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    input, output.toString(),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(),
                    response.ttftMs(), response.cachedTokens(), null));
            return response;
        } catch (Exception e) {
            if (!received) usages.add(LlmUsage.of(role, model, null));
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    input, null,
                    System.currentTimeMillis() - start, null, null, null, null, e.getMessage()));
            throw e;
        }
    }

    /** Answer 始终使用 normal；与 Planner 共用同一份模型单价配置。 */
    public LlmUsage answerUsage(ChatResponse response) {
        return LlmUsage.of("normal", llmConfig.getNormal(), response);
    }

    /** 最终对账/绑定/历史输入保护均结束后记录结果，不能把提前产生的 PLAN_COMPILED 当作可执行。 */
    private void recordAttempt(String traceId, TraceSink sink, int attempt, String retryReason,
            String callStatus, String validationStatus, String result, String error,
            com.cexpilot.metric.CompiledPlan plan) {
        LlmClient client = attempt == 1 ? llm : repairLlm;
        boolean useRepair = attempt > 1 && repairLlm != llm;
        var model = useRepair ? llmConfig.getFlagship() : llmConfig.getNormal();
        ObjectNode input = MAPPER.createObjectNode().put("attempt", attempt)
                .put("model_role", useRepair ? "flagship" : "normal")
                .put("model", client.requestParameters(null).path("model").asText(model.getModel()));
        input.put("retry_reason", retryReason);
        ObjectNode output = MAPPER.createObjectNode().put("call_status", callStatus)
                .put("validation_status", validationStatus).put("result", result);
        output.put("call_error", "FAILED".equals(callStatus) ? error : null);
        output.put("validation_error", "FAILED".equals(validationStatus) ? error : null);
        if (plan != null) {
            output.set("logical_plan", plan.logicalPlanJson());
            output.set("compiled_plan", plan.dagPlan().toJson());
        }
        sink.record(new TraceEvent(traceId, "PLANNER_ATTEMPT", TRACE_NAME, input.toString(), output.toString(),
                null, null, null, null, null, error));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private String eventInput(int attempt, List<ChatMessage> messages, JsonNode responseFormat,
                              LlmClient client, String role, String model) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("stage", TRACE_NAME);
        node.put("attempt", attempt);
        node.put("message_count", messages.size());
        node.set("messages", LlmTraceSerializer.messagesToArray(messages));
        client.requestParameters(responseFormat).fields().forEachRemaining(field -> node.set(field.getKey(), field.getValue()));
        node.put("model_role", role);
        if (!node.hasNonNull("model")) node.put("model", model);
        // 显式 null 表示本次没有下发结构约束；完整 Schema 保留在 response_format 中。
        node.set("response_format", responseFormat == null
                ? com.fasterxml.jackson.databind.node.NullNode.instance : responseFormat.deepCopy());
        if (responseFormat != null && responseFormat.path("json_schema").has("schema")) {
            node.put("schema_fingerprint", PromptStore.fingerprint(responseFormat.at("/json_schema/schema").toString()));
        }
        return node.toString();
    }

    /** 把 planner 原始输出包成 JSON 对象落 trace（PLAN 事件的 output_json）。 */
    private static String planOutput(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", content);
        return node.toString();
    }
}
