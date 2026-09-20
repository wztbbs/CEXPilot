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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * LLM 动态规划器（合并调用）：一次 LLM 调用同时完成领域判断、intent 归类（统计 hint）
 * 与 Plan 生成。渲染 dag_planner prompt（注入对话上下文、intent 列表、全量已注册工具
 * 与规模约束），LLM 不带 tools 输出 {"in_domain", "intent", "reply", "plan"}：
 * - in_domain=false → 直接接受（reply 为产品边界话术），不 repair；
 * - in_domain=true 且 plan=null → 模型有意不规划（工具不足以回答或需追问），不 repair；
 *   plan 存在但 nodes 为空且 reply 非空时同理（模型常这么表达追问），直接接受；
 *   但 reply 超长（>100 字）视为模型把推理过程倒进了 reply 的协议误用，走 repair；
 * - in_domain=true 且 plan 非空 → LlmJson 容错解析 + PlanValidator 确定性校验，
 *   失败把错误明细追加为消息让 LLM 修复，最多重试 plannerMaxRetries 次；
 * - 信封缺失（非对象 / 没有 in_domain 字段，如直接输出 nodes 裸数组）→ 先抢救：
 *   能解析出合法 plan 就按在域接受；救不回来视为格式错误走 repair，
 *   绝不误判为出域（否则格式抖动会被静默吞成边界话术）。
 *
 * 每次 LLM 调用落 LLM_CALL trace（name="dag_planner"），每次生成的输出落 PLAN trace
 * （校验通过、有意不规划或抢救成功时 error 为 null，否则带错误明细）。
 *
 * dag.planner-response-format 配置 json_object / json_schema 时，planner 调用会下发
 * OpenAI 兼容 response_format 约束（json_schema 强制信封结构，需模型支持）。
 */
@Component
public class DagPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROMPT_NAME = "dag_planner";
    private static final String TRACE_NAME = "dag_planner";
    /** 透传 reply 的长度上限：合法追问/边界话术都很短，超长基本是把推理过程倒进了 reply。 */
    private static final int REPLY_MAX_LENGTH = 100;

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
        String systemPrompt = systemPrompt(conversationContext);
        JsonNode responseFormat = plannerResponseFormat();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(question));

        int promptTokens = 0;
        int completionTokens = 0;
        String lastError = null;
        int attempts = dagConfig.getPlannerMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            ChatResponse response = callLlm(messages, traceId, sink, attempt, responseFormat);
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

            if (!parsed.isObject() || !parsed.has("in_domain")) {
                // 模型丢了信封（如直接输出 nodes 裸数组）：先抢救，救不回来再走 repair
                Optional<DagPlan> salvaged = salvagePlan(parsed, maxToolCalls);
                if (salvaged.isPresent()) {
                    DagPlan plan = salvaged.get();
                    sink.record(TraceEvent.plan(traceId, plan.toJson().toString(), null));
                    return new PlanOutcome(true, IntentRegistry.UNKNOWN, null,
                            Optional.of(plan), promptTokens, completionTokens, null);
                }
                lastError = "输出缺少信封：必须输出完整 JSON 对象 {\"in_domain\", \"intent\", \"reply\", \"plan\"}，"
                        + "不要直接输出 nodes 数组";
                sink.record(TraceEvent.plan(traceId, parsed.toString(), lastError));
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
                if (isReasoningDump(reply)) {
                    // 模型把推理过程倒进 reply 且没给 plan：协议误用，按格式错误 repair，
                    // 不能直接接受——否则推理原文会被透传成答案
                    lastError = "reply 只能写一句要对用户说的简短话术（" + REPLY_MAX_LENGTH
                            + " 字以内），禁止输出推理过程；问题可查询时必须输出 plan 字段";
                    sink.record(TraceEvent.plan(traceId, parsed.toString(), lastError));
                    appendRepair(messages, response.content(), lastError);
                    continue;
                }
                // 模型有意不规划（plan=null）：工具不足以回答或需追问，不 repair
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                return new PlanOutcome(true, intent, reply,
                        Optional.empty(), promptTokens, completionTokens, null);
            }

            try {
                DagPlan plan = DagPlan.fromJson(planNode);
                if (plan.nodes().isEmpty() && reply != null && !reply.isBlank()) {
                    if (isReasoningDump(reply)) {
                        lastError = "reply 只能写一句要对用户说的简短话术（" + REPLY_MAX_LENGTH
                                + " 字以内），禁止输出推理过程；问题可查询时必须输出 plan 字段";
                        sink.record(TraceEvent.plan(traceId, parsed.toString(), lastError));
                        appendRepair(messages, response.content(), lastError);
                        continue;
                    }
                    // 空 nodes + reply：模型有意不规划（向用户追问或说明能力缺口），
                    // 等价于 plan=null，直接接受不 repair——否则重试三次后 reply 还会被丢掉
                    sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                    return new PlanOutcome(true, intent, reply,
                            Optional.empty(), promptTokens, completionTokens, null);
                }
                List<String> errors = validator.validate(plan, null, maxToolCalls);
                if (errors.isEmpty()) {
                    sink.record(TraceEvent.plan(traceId, plan.toJson().toString(), null));
                    // reply 与 plan 并存时只作查询缺口说明；推理 dump 直接丢弃
                    // （plan 已合法，不值得为 reply 再走一轮 repair）
                    String effectiveReply = isReasoningDump(reply) ? null : reply;
                    return new PlanOutcome(true, intent, effectiveReply,
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

    /** 判断 reply 是否是推理 dump：合法话术很短，超长即视为协议误用。 */
    private static boolean isReasoningDump(String reply) {
        return reply != null && reply.length() > REPLY_MAX_LENGTH;
    }

    /**
     * 信封缺失时的抢救：裸 nodes 数组、{"plan": {...}}、{"nodes": [...]} 都视为
     * 模型判断正确但包装丢失；内容能通过 PlanValidator 校验就接受，否则返回空走 repair。
     */    private Optional<DagPlan> salvagePlan(JsonNode parsed, int maxToolCalls) {
        JsonNode planNode = null;
        if (parsed.isArray()) {
            ObjectNode wrapped = MAPPER.createObjectNode();
            wrapped.set("nodes", parsed);
            planNode = wrapped;
        } else if (parsed.isObject()) {
            if (parsed.path("plan").isObject()) {
                planNode = parsed.path("plan");
            } else if (parsed.path("nodes").isArray()) {
                planNode = parsed;
            }
        }
        if (planNode == null) {
            return Optional.empty();
        }
        try {
            DagPlan plan = DagPlan.fromJson(planNode);
            if (plan.nodes().isEmpty()) {
                return Optional.empty();
            }
            return validator.validate(plan, null, maxToolCalls).isEmpty()
                    ? Optional.of(plan) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 按 dag.planner-response-format 构造下发给 planner 调用的 response_format；空配置 = 不下发。 */
    private JsonNode plannerResponseFormat() {
        String format = dagConfig.getPlannerResponseFormat();
        if (format == null || format.isBlank()) {
            return null;
        }
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
     * planner 输出信封的 JSON Schema。plan 不列入 required：模型判断工具不足以回答时
     * 可以省略 plan（等价于协议里的 plan=null）；reply/intent 同理允许省略。
     */
    private static JsonNode plannerSchema() {
        try {
            return MAPPER.readTree("""
                    {"type": "object", "additionalProperties": false,
                     "properties": {
                       "in_domain": {"type": "boolean"},
                       "intent": {"type": "string"},
                       "reply": {"type": "string"},
                       "plan": {"type": "object", "additionalProperties": false,
                         "properties": {"nodes": {"type": "array", "items": {
                           "type": "object", "additionalProperties": false,
                           "properties": {
                             "id": {"type": "string"},
                             "tool": {"type": "string"},
                             "args": {"type": "object"},
                             "depends_on": {"type": "array", "items": {"type": "string"}},
                             "include_details": {"type": "boolean"}
                           },
                           "required": ["id", "tool"]
                         }}},
                         "required": ["nodes"]}
                     },
                     "required": ["in_domain"]}
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("planner schema 内置常量解析失败", e);
        }
    }

    private String systemPrompt(String conversationContext) {
        return prompts.render(PROMPT_NAME, Map.of(
                "intents", renderIntentList(),
                "tools", renderTools(registry.specs()),
                "max_nodes", String.valueOf(Math.min(dagConfig.getMaxNodes(), llmConfig.getMaxToolCalls())),
                "max_depth", String.valueOf(dagConfig.getMaxDepth()),
                "conversation_context", conversationContext == null ? "" : conversationContext));
    }

    /** 不含每轮历史的有效规划提示词版本，包含实际注入的工具和意图配置。 */
    public String promptVersion() {
        return PromptStore.fingerprint(systemPrompt(""));
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

    private ChatResponse callLlm(List<ChatMessage> messages, String traceId, TraceSink sink, int attempt,
                                 JsonNode responseFormat) {
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = llm.chat(messages, null, responseFormat);
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
        Map<String, JsonNode> common = new java.util.LinkedHashMap<>();
        Map<String, Integer> counts = new java.util.HashMap<>();
        Set<String> different = new HashSet<>();
        for (ToolSpec spec : specs) {
            spec.inputSchema().path("properties").fields().forEachRemaining(field -> {
                JsonNode previous = common.putIfAbsent(field.getKey(), field.getValue());
                if (previous != null && !previous.equals(field.getValue())) different.add(field.getKey());
                counts.merge(field.getKey(), 1, Integer::sum);
            });
        }
        common.keySet().removeIf(key -> counts.get(key) < 2 || different.contains(key));
        StringBuilder sb = new StringBuilder();
        if (!common.isEmpty()) {
            sb.append("公共参数定义（仅适用于列出该参数的工具）：\n");
            common.forEach((name, schema) -> sb.append("  ").append(name)
                    .append('(').append(renderParamDefinition(schema)).append(")\n"));
        }
        for (ToolSpec spec : specs) {
            sb.append("- ").append(spec.name()).append("：").append(spec.description()).append('\n')
                    .append("  参数：").append(renderParams(spec.inputSchema(), common.keySet())).append('\n');
        }
        return sb.toString();
    }

    private static String renderParams(JsonNode schema, Set<String> common) {
        JsonNode properties = schema.path("properties");
        if (properties.isEmpty()) return "无";
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        List<String> params = new ArrayList<>();
        properties.fields().forEachRemaining(field -> {
            String param = field.getKey() + (required.contains(field.getKey()) ? "*" : "");
            if (!common.contains(field.getKey())) param += "(" + renderParamDefinition(field.getValue()) + ")";
            params.add(param);
        });
        return String.join(", ", params);
    }

    private static String renderParamDefinition(JsonNode field) {
        List<String> parts = new ArrayList<>();
        parts.add(field.path("type").asText());
        if (field.path("enum").isArray()) {
            List<String> values = new ArrayList<>();
            field.get("enum").forEach(value -> values.add(value.asText()));
            parts.add(String.join("|", values));
        }
        for (String key : List.of("default", "minimum", "maximum", "pattern")) {
            if (field.has(key)) parts.add(key + "=" + field.get(key).asText());
        }
        String description = field.path("description").asText("").trim();
        if (!description.isEmpty()) parts.add(description);
        return String.join(", ", parts);
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
