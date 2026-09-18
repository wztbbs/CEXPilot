package com.cexpilot.dag;

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

/**
 * DAG 运行时（替代原 ReAct 循环）：一次问答 = DagPlanner 合并调用（领域判断 + intent 归类
 * + 规划）→ DagExecutor 并行执行 → 汇总 evidence → 1 次不带工具的 LLM 调用生成最终回答。
 *
 * 分支：
 * - 出域：不执行工具、不调 answer LLM，直接用 planner 给的 reply（为空用固定话术）；
 * - 在域且 Plan 合法：执行 DAG 后基于证据回答；
 * - 在域但 Plan 为空（工具不足或 repair 耗尽）：降级为 LLM 基于已有知识直接回答
 *   （user 消息注明局限），evidence 为空数组。
 */
@Component
public class DagRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(DagRuntime.class);
    private static final String ANSWER_PROMPT_NAME = "agent_system";
    private static final String PLANNER_PROMPT_NAME = "dag_planner";
    private static final String OUT_OF_DOMAIN_FALLBACK =
            "我只支持 web3 / 加密货币交易领域的问题，暂时无法回答其他类型的问题。";

    private final LlmClient llm;
    private final DagPlanner planner;
    private final DagExecutor executor;
    private final PromptStore prompts;

    public DagRuntime(LlmClient llm, DagPlanner planner, DagExecutor executor, PromptStore prompts) {
        this.llm = llm;
        this.planner = planner;
        this.executor = executor;
        this.prompts = prompts;
    }

    public ExecutionResult execute(String question, String conversationContext, String traceId, TraceSink sink) {
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

        String userContent;
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
            userContent = question + "\n\n<EVIDENCE>\n" + evidence.toString()
                    + "\n</EVIDENCE>\n以上是基于你的问题查询到的真实数据（JSON）。请基于这些证据回答，证据不足的部分明确说明。";
        } else {
            // 未执行任何工具的降级路径：模型判断工具不足，或规划 repair 耗尽
            String reason = outcome.lastError() != null
                    ? "规划失败，降级为直接回答: " + outcome.lastError()
                    : "现有工具不足以回答，降级为直接回答";
            log.warn("traceId={} {}", traceId, reason);
            sink.record(TraceEvent.plan(traceId, null, reason));
            userContent = question + "\n\n（系统未能为这个问题规划数据查询，请基于已有知识谨慎回答，"
                    + "并明确说明回答未经过实时数据验证、可能存在偏差。）";
        }

        String systemPrompt = prompts.render(ANSWER_PROMPT_NAME,
                Map.of("conversation_context", conversationContext == null ? "" : conversationContext));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(userContent));
        ChatResponse answer = callAnswerLlm(messages, traceId, sink);
        totalPromptTokens += answer.promptTokens() == null ? 0 : answer.promptTokens();
        totalCompletionTokens += answer.completionTokens() == null ? 0 : answer.completionTokens();

        return new ExecutionResult(answer.content(), evidence, toolCallCount, steps,
                totalPromptTokens, totalCompletionTokens, outcome.intent());
    }

    private ChatResponse callAnswerLlm(List<ChatMessage> messages, String traceId, TraceSink sink) {
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = llm.chat(messages, null);
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
        return prompts.version(PLANNER_PROMPT_NAME);
    }
}
