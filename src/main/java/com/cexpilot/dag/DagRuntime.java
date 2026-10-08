package com.cexpilot.dag;

import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.LlmTraceSerializer;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DAG 运行时（替代原 ReAct 循环）：一次问答 = DagPlanner 合并调用（领域判断 + 规划）→ DagExecutor 并行执行 → 汇总 evidence → 1 次不带工具的 LLM 调用生成最终回答。
 *
 * 工具返回的完整 evidence 直接作为回答的 FACTS，不在回答前删除明细；统一按问题与证据适用事实约束。
 * 出域直接返回边界话术；planner 有意不规划且给出 reply（追问/能力缺口）时直接透传为答案；
 * 规划失败或没有计划时也直接返回，禁止再让 Answer 用空事实生成答案。
 */
@Component
public class DagRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ANSWER_PROMPT_NAME = "agent_system";
    // 与行情工具未携带用户时区时的默认口径一致，不使用服务器本地时区。
    private static final ZoneId DEFAULT_USER_ZONE = ZoneOffset.ofHours(8);
    private static final String OUT_OF_DOMAIN_FALLBACK =
            "我只支持 web3 / 加密货币交易领域的问题，暂时无法回答其他类型的问题。";

    private final LlmClient llm;
    private final DagPlanner planner;
    private final DagExecutor executor;
    private final PromptStore prompts;
    private final Clock clock;

    public DagRuntime(LlmClient llm, DagPlanner planner, DagExecutor executor, PromptStore prompts,
                      Clock clock) {
        this.llm = llm;
        this.planner = planner;
        this.executor = executor;
        this.prompts = prompts;
        this.clock = clock;
    }

    public ExecutionResult execute(String question, String conversationContext, String traceId, TraceSink sink) {
        return execute(question, conversationContext, traceId, sink, null, null);
    }

    /**
     * @param answerDelta 非 null 时 answer 阶段走流式调用，逐段回调答案增量（用于 SSE 推送）；
     *                    trace 落库与聚合逻辑不变。
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta) {
        return execute(question, conversationContext, traceId, sink, answerDelta, null);
    }

    /**
     * @param requestContext 请求时间上下文（用户时区 + 固定 requestTime），随 ToolContext
     *                       传给每个工具节点和 Answer；缺省值在规划前统一补齐
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, null);
    }

    /**
     * @param progress 非 null 时在阶段边界回调进度文案（SSE 推给前端展示"执行到哪一步"）
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress) {
        RequestContext effectiveContext = resolveRequestContext(requestContext);
        ObjectNode timeContext = answerTimeContext(effectiveContext,
                requestContext != null && requestContext.userZone() != null);
        emit(progress, "正在生成执行计划…");
        DagPlanner.PlanOutcome outcome = planner.plan(question, conversationContext, traceId, sink);
        int totalPromptTokens = outcome.promptTokens();
        int totalCompletionTokens = outcome.completionTokens();

        if (!outcome.inDomain()) {
            String answer = outcome.reply() == null || outcome.reply().isBlank()
                    ? OUT_OF_DOMAIN_FALLBACK : outcome.reply();
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens);
        }

        // 没有获准执行的计划，就没有 Answer 调用；拒答也走同一个 SSE 返回路径。
        if (outcome.plan().isEmpty()) {
            String answer = outcome.lastError() != null
                    ? "暂时无法可靠生成查询计划，本次未执行查询。请明确交易对和时间要求后重试。"
                    : outcome.reply() != null && !outcome.reply().isBlank() ? outcome.reply()
                    : "当前没有能够可靠回答此问题的查询计划，暂时无法处理。";
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens);
        }

        ArrayNode evidence = MAPPER.createArrayNode();
        int toolCallCount = 0;
        int steps = 0;

        ObjectNode queryStatus = MAPPER.createObjectNode();
        // reply 是 planner 写的查询缺口说明；模型偶尔会把推理过程倒进来，截断防污染
        if (outcome.reply() != null) {
            String missing = outcome.reply();
            queryStatus.put("missing", missing.length() <= 200 ? missing : missing.substring(0, 200));
        }
        DagPlan plan = outcome.plan().orElseThrow();
        emit(progress, "执行计划已生成（" + plan.nodes().size() + " 项查询），正在获取数据…");
        DagExecutor.ExecutionOutcome execution = executor.execute(plan, traceId, sink, effectiveContext);
        emit(progress, "数据获取完毕，正在组装答案…");
        steps = execution.layers();
        for (PlanNode node : plan.nodes()) {
            ToolResult result = execution.context().get(node.id());
            if (result == null) {
                continue;
            }
            toolCallCount++;
            appendEvidence(evidence, node, result);
        }
        String userContent = question + "\n\n<FACTS>\n" + evidence + "\n</FACTS>\n<QUERY_STATUS>\n"
                + queryStatus + "\n</QUERY_STATUS>";

        String systemPrompt = prompts.render(ANSWER_PROMPT_NAME, Map.of(
                "time_context", timeContext.toString(),
                "conversation_context", conversationContext == null ? "" : conversationContext));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(userContent));
        ChatResponse answer = callAnswerLlm(messages, traceId, sink, answerDelta, timeContext);
        totalPromptTokens += answer.promptTokens() == null ? 0 : answer.promptTokens();
        totalCompletionTokens += answer.completionTokens() == null ? 0 : answer.completionTokens();

        return new ExecutionResult(answer.content(), evidence, toolCallCount, steps,
                totalPromptTokens, totalCompletionTokens);
    }

    private static void emit(Consumer<String> progress, String text) {
        if (progress != null) {
            progress.accept(text);
        }
    }

    private ChatResponse callAnswerLlm(List<ChatMessage> messages, String traceId, TraceSink sink,
                                       Consumer<String> answerDelta, ObjectNode timeContext) {
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = answerDelta == null
                    ? llm.chat(messages, null)
                    : llm.chatStream(messages, null, answerDelta);
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(messages, timeContext), LlmTraceSerializer.responseToJson(response),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(),
                    response.ttftMs(), response.cachedTokens(), null));
            return response;
        } catch (Exception e) {
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(messages, timeContext), null,
                    System.currentTimeMillis() - start, null, null, null, null, e.getMessage()));
            throw e;
        }
    }

    private RequestContext resolveRequestContext(RequestContext context) {
        return new RequestContext(
                context != null && context.userZone() != null ? context.userZone() : DEFAULT_USER_ZONE,
                context != null && context.requestTime() != null ? context.requestTime() : clock.instant());
    }

    private static ObjectNode answerTimeContext(RequestContext context, boolean userZoneProvided) {
        var localTime = context.requestTime().atZone(context.userZone());
        ObjectNode node = MAPPER.createObjectNode();
        node.put("request_time_utc", context.requestTime().toString());
        node.put("timezone", context.userZone().getId());
        node.put("timezone_source", userZoneProvided ? "request" : "default");
        node.put("request_time_local", localTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        node.put("current_date", localTime.toLocalDate().toString());
        return node;
    }

    private void appendEvidence(ArrayNode evidence, PlanNode node, ToolResult result) {
        ObjectNode entry = evidence.addObject();
        entry.put("node_id", node.id());
        if (node.metric() != null) {
            entry.put("type", "metric");
            entry.set("identity", node.metric().identity());
        } else {
            entry.put("type", "calculation");
            entry.put("operator", node.tool());
            entry.set("input_references", node.args());
        }
        entry.put("ok", result.ok());
        if (result.data() != null) {
            entry.set("data", result.data());
        }
        if (result.error() != null) {
            entry.put("error", result.error());
        }
    }

    private static String answerEventInput(List<ChatMessage> messages, ObjectNode timeContext) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("stage", "answer");
        node.put("message_count", messages.size());
        node.set("time_context", timeContext);
        node.set("messages", LlmTraceSerializer.messagesToArray(messages));
        return node.toString();
    }

    public String promptVersion() {
        return PromptStore.fingerprint(planner.promptVersion() + prompts.version(ANSWER_PROMPT_NAME));
    }
}
