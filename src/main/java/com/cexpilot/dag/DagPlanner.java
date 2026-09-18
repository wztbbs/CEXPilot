package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.intent.IntentDefinition;
import com.cexpilot.intent.IntentRegistry;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.LlmJson;
import com.cexpilot.llm.ToolSpec;
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

/**
 * LLM 动态规划器（合并调用）：一次 LLM 调用同时完成领域判断、intent 归类（统计 hint）
 * 与 Plan 生成。渲染 dag_planner prompt（注入对话上下文、intent 列表、全量已注册工具
 * 与规模约束），LLM 不带 tools 输出 {"in_domain", "intent", "reply", "plan"}：
 * - in_domain=false → 直接接受（reply 为产品边界话术），不 repair；
 * - in_domain=true 且 plan=null → 模型有意不规划（工具不足以回答），不 repair；
 * - in_domain=true 且 plan 非空 → LlmJson 容错解析 + PlanValidator 确定性校验，
 *   失败把错误明细追加为消息让 LLM 修复，最多重试 plannerMaxRetries 次。
 *
 * 每次 LLM 调用落 LLM_CALL trace（name="dag_planner"），每次生成的输出落 PLAN trace
 * （校验通过或有意不规划时 error 为 null，否则带错误明细）。
 */
@Component
public class DagPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROMPT_NAME = "dag_planner";
    private static final String TRACE_NAME = "dag_planner";

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final IntentRegistry intentRegistry;
    private final LlmConfig llmConfig;
    private final DagConfig dagConfig;
    private final PromptStore prompts;
    private final PlanValidator validator;

    public DagPlanner(LlmClient llm, ToolRegistry registry, IntentRegistry intentRegistry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator) {
        this.llm = llm;
        this.registry = registry;
        this.intentRegistry = intentRegistry;
        this.llmConfig = llmConfig;
        this.dagConfig = dagConfig;
        this.prompts = prompts;
        this.validator = validator;
    }

    /**
     * 规划结果。inDomain=false 时 reply 为边界话术；inDomain=true 且 plan 为空表示
     * 模型判断工具不足以回答（reply 说明缺口）或 repair 耗尽（lastError 非空）。
     */
    public record PlanOutcome(boolean inDomain, String intent, String reply,
                              Optional<DagPlan> plan, int promptTokens, int completionTokens,
                              String lastError) {
    }

    public PlanOutcome plan(String question, String conversationContext, String traceId, TraceSink sink) {
        int maxToolCalls = llmConfig.getMaxToolCalls();
        int maxNodes = Math.min(dagConfig.getMaxNodes(), maxToolCalls);

        String systemPrompt = prompts.render(PROMPT_NAME, Map.of(
                "intents", renderIntentList(),
                "tools", renderTools(registry.specs()),
                "max_nodes", String.valueOf(maxNodes),
                "max_depth", String.valueOf(dagConfig.getMaxDepth()),
                "conversation_context", conversationContext == null ? "" : conversationContext));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(question));

        int promptTokens = 0;
        int completionTokens = 0;
        String lastError = null;
        int attempts = dagConfig.getPlannerMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            ChatResponse response = callLlm(messages, traceId, sink, attempt);
            promptTokens += response.promptTokens() == null ? 0 : response.promptTokens();
            completionTokens += response.completionTokens() == null ? 0 : response.completionTokens();

            JsonNode parsed;
            try {
                parsed = LlmJson.parse(response.content());
            } catch (Exception e) {
                lastError = "输出 JSON 解析失败: " + e.getMessage();
                sink.record(TraceEvent.plan(traceId, rawOutput(response.content()), lastError));
                appendRepair(messages, response.content(), lastError);
                continue;
            }

            if (!parsed.path("in_domain").asBoolean(false)) {
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                return new PlanOutcome(false, null, textOrNull(parsed.path("reply")),
                        Optional.empty(), promptTokens, completionTokens, null);
            }

            String intent = normalizeIntent(parsed.path("intent"));
            String reply = textOrNull(parsed.path("reply"));
            JsonNode planNode = parsed.path("plan");
            if (!planNode.isObject()) {
                // 模型有意不规划（plan=null）：工具不足以回答，不 repair
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                return new PlanOutcome(true, intent, reply,
                        Optional.empty(), promptTokens, completionTokens, null);
            }

            try {
                DagPlan plan = DagPlan.fromJson(planNode);
                List<String> errors = validator.validate(plan, null, maxToolCalls);
                if (errors.isEmpty()) {
                    sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                    return new PlanOutcome(true, intent, reply,
                            Optional.of(plan), promptTokens, completionTokens, null);
                }
                lastError = "plan 校验失败: " + String.join("; ", errors);
            } catch (Exception e) {
                lastError = "plan 解析失败: " + e.getMessage();
            }
            sink.record(TraceEvent.plan(traceId, parsed.toString(), lastError));
            appendRepair(messages, response.content(), lastError);
        }
        // repair 耗尽：按在域处理走降级回答，intent 记 UNKNOWN
        return new PlanOutcome(true, IntentRegistry.UNKNOWN, null,
                Optional.empty(), promptTokens, completionTokens, lastError);
    }

    /** LLM 编造未注册的 intent 名时记 UNKNOWN，防止编造的名字进入统计。 */
    private String normalizeIntent(JsonNode node) {
        if (node.isTextual()) {
            String name = node.asText();
            if (IntentRegistry.UNKNOWN.equals(name) || intentRegistry.find(name) != null) {
                return name;
            }
        }
        return IntentRegistry.UNKNOWN;
    }

    private void appendRepair(List<ChatMessage> messages, String badOutput, String error) {
        messages.add(ChatMessage.assistant(badOutput, null));
        messages.add(ChatMessage.user("上一次输出不合法，请修正后重新输出完整 JSON，只输出 JSON。错误明细：\n" + error));
    }

    private ChatResponse callLlm(List<ChatMessage> messages, String traceId, TraceSink sink, int attempt) {
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = llm.chat(messages, null);
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    eventInput(attempt, messages.size()), rawOutput(response.content()),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(), null));
            return response;
        } catch (Exception e) {
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    eventInput(attempt, messages.size()), null,
                    System.currentTimeMillis() - start, null, null, e.getMessage()));
            throw e;
        }
    }

    private String renderIntentList() {
        StringBuilder sb = new StringBuilder();
        for (IntentDefinition intent : intentRegistry.all()) {
            sb.append("- ").append(intent.name());
            if (intent.description() != null && !intent.description().isBlank()) {
                sb.append("：").append(intent.description().trim());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String renderTools(List<ToolSpec> specs) {
        StringBuilder sb = new StringBuilder();
        for (ToolSpec spec : specs) {
            sb.append("- ").append(spec.name())
                    .append("：").append(spec.description()).append('\n')
                    .append("  入参 JSON Schema：").append(spec.inputSchema().toString()).append('\n');
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private static String eventInput(int attempt, int messageCount) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("stage", TRACE_NAME);
        node.put("attempt", attempt);
        node.put("message_count", messageCount);
        return node.toString();
    }

    /** 把 LLM 原始输出包成 JSON 对象落 trace（output_json 是 JSON 列，原始文本可能不是合法 JSON）。 */
    private static String rawOutput(String content) {
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
}
