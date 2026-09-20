package com.cexpilot.dag;

import com.cexpilot.intent.IntentDefinition;
import com.cexpilot.intent.IntentRegistry;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * DAG 运行时（替代原 ReAct 循环）：一次问答 = DagPlanner 合并调用（领域判断 + intent 归类
 * + 规划）→ DagExecutor 并行执行 → 汇总 evidence → 1 次不带工具的 LLM 调用生成最终回答。
 *
 * 按计划保留回答所需明细，其余投影为摘要；所有意图共享同一事实约束模板，
 * 命中意图时追加该意图的 evidence_policy.rules 作为回答要求。
 * 出域直接返回边界话术；无计划时传空 FACTS 和查询缺口，禁止凭记忆降级回答。
 */
@Component
public class DagRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(DagRuntime.class);
    private static final String ANSWER_PROMPT_NAME = "agent_system";
    private static final String OUT_OF_DOMAIN_FALLBACK =
            "我只支持 web3 / 加密货币交易领域的问题，暂时无法回答其他类型的问题。";

    private final LlmClient llm;
    private final DagPlanner planner;
    private final DagExecutor executor;
    private final PromptStore prompts;
    private final IntentRegistry intentRegistry;

    public DagRuntime(LlmClient llm, DagPlanner planner, DagExecutor executor, PromptStore prompts,
                      IntentRegistry intentRegistry) {
        this.llm = llm;
        this.planner = planner;
        this.executor = executor;
        this.prompts = prompts;
        this.intentRegistry = intentRegistry;
    }

    public ExecutionResult execute(String question, String conversationContext, String traceId, TraceSink sink) {
        return execute(question, conversationContext, traceId, sink, null);
    }

    /**
     * @param answerDelta 非 null 时 answer 阶段走流式调用，逐段回调答案增量（用于 SSE 推送）；
     *                    trace 落库与聚合逻辑不变。
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta) {
        DagPlanner.PlanOutcome outcome = planner.plan(question, conversationContext, traceId, sink);
        int totalPromptTokens = outcome.promptTokens();
        int totalCompletionTokens = outcome.completionTokens();

        if (!outcome.inDomain()) {
            String answer = outcome.reply() == null || outcome.reply().isBlank()
                    ? OUT_OF_DOMAIN_FALLBACK : outcome.reply();
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens, null);
        }

        ArrayNode evidence = MAPPER.createArrayNode();
        int toolCallCount = 0;
        int steps = 0;

        ArrayNode facts = MAPPER.createArrayNode();
        ObjectNode queryStatus = MAPPER.createObjectNode();
        if (outcome.reply() != null) queryStatus.put("missing", outcome.reply());
        if (outcome.plan().isPresent()) {
            DagPlan plan = outcome.plan().get();
            DagExecutor.ExecutionOutcome execution = executor.execute(plan, traceId, sink);
            steps = execution.layers();
            for (PlanNode node : plan.nodes()) {
                ToolResult result = execution.context().get(node.id());
                if (result == null) {
                    continue;
                }
                toolCallCount++;
                appendEvidence(evidence, node.id(), node.tool(), result);
            }
            Set<String> keepDetails = plan.nodes().stream().filter(PlanNode::includeDetails)
                    .map(PlanNode::id).collect(java.util.stream.Collectors.toSet());
            facts = EvidenceSummarizer.summarize(evidence, keepDetails);
        } else {
            String reason = outcome.lastError() != null ? "查询规划失败" : "没有可执行的查询计划";
            log.warn("traceId={} {}", traceId, reason);
            sink.record(TraceEvent.plan(traceId, null, reason));
            queryStatus.put("status", reason);
        }
        String userContent = question + "\n\n<FACTS>\n" + facts + "\n</FACTS>\n<QUERY_STATUS>\n"
                + queryStatus + "\n</QUERY_STATUS>";

        String systemPrompt = prompts.render(ANSWER_PROMPT_NAME, Map.of(
                "conversation_context", conversationContext == null ? "" : conversationContext,
                "intent_guidance", intentGuidance(outcome.intent())));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(userContent));
        ChatResponse answer = callAnswerLlm(messages, traceId, sink, answerDelta);
        totalPromptTokens += answer.promptTokens() == null ? 0 : answer.promptTokens();
        totalCompletionTokens += answer.completionTokens() == null ? 0 : answer.completionTokens();

        return new ExecutionResult(answer.content(), evidence, toolCallCount, steps,
                totalPromptTokens, totalCompletionTokens, outcome.intent());
    }

    private ChatResponse callAnswerLlm(List<ChatMessage> messages, String traceId, TraceSink sink,
                                       Consumer<String> answerDelta) {
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = answerDelta == null
                    ? llm.chat(messages, null)
                    : llm.chatStream(messages, null, answerDelta);
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(), answerEventOutput(response.content()),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(), null));
            return response;
        } catch (Exception e) {
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(), null,
                    System.currentTimeMillis() - start, null, null, e.getMessage()));
            throw e;
        }
    }

    /** 命中意图时，把该意图的证据规则注入回答 prompt；未命中或无规则时为空。 */
    private String intentGuidance(String intent) {
        IntentDefinition definition = intentRegistry.find(intent);
        if (definition == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本轮问题归类为 ").append(definition.name());
        if (definition.description() != null && !definition.description().isBlank()) {
            sb.append("（").append(definition.description().trim()).append("）");
        }
        if (!definition.evidenceRules().isEmpty()) {
            sb.append("。该类别问题的回答要求：");
            for (String rule : definition.evidenceRules()) {
                sb.append("\n- ").append(rule);
            }
        }
        return sb.toString();
    }

    private void appendEvidence(ArrayNode evidence, String nodeId, String toolName, ToolResult result) {
        ObjectNode entry = evidence.addObject();
        entry.put("node_id", nodeId);
        entry.put("tool", toolName);
        entry.put("ok", result.ok());
        if (result.data() != null) {
            entry.set("data", result.data());
        }
        if (result.error() != null) {
            entry.put("error", result.error());
        }
    }

    private static String answerEventInput() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("stage", "answer");
        return node.toString();
    }

    private static String answerEventOutput(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", abbreviate(content));
        return node.toString();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "...";
    }

    public String promptVersion() {
        return PromptStore.fingerprint(planner.promptVersion() + prompts.version(ANSWER_PROMPT_NAME));
    }
}
