package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.dag.guard.GuardContext;
import com.cexpilot.dag.guard.QueryCapabilityGuard;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * - in_domain=true 且 plan=null → reply 非空时视为模型有意不规划（工具不足以回答或需追问），
 *   不 repair；plan 存在但 nodes 为空且 reply 非空时同理（模型常这么表达追问），直接接受；
 *   reply 为空则既不是规划也不是话术，属协议违约，走 repair；
 *   reply 超长（>100 字）视为模型把推理过程倒进了 reply 的协议误用，走 repair；
 * - in_domain=true 且 plan 非空 → LlmJson 容错解析 + PlanValidator 确定性校验，
 *   失败把错误明细追加为消息让 LLM 修复，最多重试 plannerMaxRetries 次；
 * - 信封缺失走格式修复；不再抢救裸 plan，避免绕过必需的 query_requirements。
 * - query_requirements 由确定性能力闸门检查；能力不足直接拒答，不进行 repair。
 *
 * 每次 LLM 调用落 LLM_CALL trace（name="dag_planner"），每次生成的输出落 PLAN trace
 * （校验通过或有意不规划时 error 为 null，否则带错误明细）。
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
    private final QueryCapabilityGuard capabilityGuard;

    public DagPlanner(LlmClient llm, ToolRegistry registry, IntentRegistry intentRegistry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator, QueryCapabilityGuard capabilityGuard) {
        this.llm = llm;
        this.registry = registry;
        this.intentRegistry = intentRegistry;
        this.llmConfig = llmConfig;
        this.dagConfig = dagConfig;
        this.prompts = prompts;
        this.validator = validator;
        this.capabilityGuard = capabilityGuard;
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

            PlanDecision decision = evaluateResponse(question, response.content(), maxToolCalls, traceId, sink);
            if (decision.error() == null) {
                return decision.toOutcome(promptTokens, completionTokens);
            }
            lastError = decision.error();
            appendRepair(messages, response.content(), lastError);
        }
        // repair 耗尽：runtime 直接返回固定失败话术，禁止继续生成无事实答案。
        return new PlanOutcome(true, IntentRegistry.UNKNOWN, null,
                Optional.empty(), promptTokens, completionTokens, lastError);
    }

    /** 单轮判断不负责 token 累计；error 非空时由 plan 统一追加修复消息并重试。 */
    private record PlanDecision(boolean inDomain, String intent, String reply, DagPlan plan, String error) {
        private static PlanDecision accepted(boolean inDomain, String intent, String reply, DagPlan plan) {
            return new PlanDecision(inDomain, intent, reply, plan, null);
        }

        private PlanOutcome toOutcome(int promptTokens, int completionTokens) {
            return new PlanOutcome(inDomain, intent, reply, Optional.ofNullable(plan),
                    promptTokens, completionTokens, error);
        }
    }

    private PlanDecision evaluateResponse(String question, String content, int maxToolCalls,
                                          String traceId, TraceSink sink) {
        JsonNode parsed;
        try {
            parsed = LlmJson.parse(content);
        } catch (Exception e) {
            return repairDecision(traceId, sink, rawOutput(content), "输出 JSON 解析失败: " + e.getMessage());
        }

        if (!parsed.isObject() || !parsed.path("in_domain").isBoolean()) {
            String error = "输出缺少信封：必须输出完整 JSON 对象 {\"in_domain\", \"intent\", \"reply\", \"plan\"}，"
                    + "不要直接输出 nodes 数组";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        if (!parsed.path("in_domain").asBoolean(false)) {
            sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
            return PlanDecision.accepted(false, null, textOrNull(parsed.path("reply")), null);
        }
        return evaluateInDomainResponse(question, parsed, maxToolCalls, traceId, sink);
    }

    private PlanDecision evaluateInDomainResponse(String question, JsonNode parsed, int maxToolCalls,
                                                  String traceId, TraceSink sink) {
        String intent = normalizeIntent(parsed.path("intent"));
        String reply = textOrNull(parsed.path("reply"));
        JsonNode planNode = parsed.path("plan");
        // 只判断任务级能力；每个查询节点的时间、市场、条数由执行链路校验。
        String refusal = capabilityGuard.refusal(GuardContext.of(parsed.path("query_requirements")));
        if (refusal != null) {
            sink.record(TraceEvent.plan(traceId, parsed.toString(), "CAPABILITY_REFUSED: " + refusal));
            return PlanDecision.accepted(true, intent, refusal, null);
        }
        if (!planNode.isObject()) {
            return evaluateReplyWithoutPlan(parsed, intent, reply, traceId, sink);
        }
        return validatePlan(parsed, intent, reply, maxToolCalls, traceId, sink);
    }

    private PlanDecision evaluateReplyWithoutPlan(JsonNode parsed, String intent, String reply,
                                                  String traceId, TraceSink sink) {
        if (reply == null || reply.isBlank()) {
            // 能力闸门已在此前放行，走到这里 plan 缺失且无任何话术 = 协议违约，必须 repair。
            String error = "缺少 plan 且未给出 reply：可查询时必须输出 plan.nodes；"
                    + "确需拒绝时必须在 reply 写明原因";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        if (isReasoningDump(reply)) {
            // 没有 plan 时，超长 reply 按协议错误修复，不能把推理原文透传成答案。
            String error = "reply 只能写一句要对用户说的简短话术（" + REPLY_MAX_LENGTH
                    + " 字以内），禁止输出推理过程；问题可查询时必须输出 plan 字段";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        // plan=null 或空 nodes + 非空 reply：模型有意不规划，直接接受不 repair。
        sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
        return PlanDecision.accepted(true, intent, reply, null);
    }

    private PlanDecision validatePlan(JsonNode parsed, String intent, String reply, int maxToolCalls,
                                      String traceId, TraceSink sink) {
        String error;
        try {
            DagPlan plan = DagPlan.fromJson(parsed.path("plan"));
            if (plan.nodes().isEmpty() && reply != null && !reply.isBlank()) {
                return evaluateReplyWithoutPlan(parsed, intent, reply, traceId, sink);
            }
            List<String> errors = validator.validate(plan, null, maxToolCalls);
            if (errors.isEmpty()) {
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                // plan 已合法时，超长 reply 直接丢弃，不再为 reply 重试。
                String effectiveReply = isReasoningDump(reply) ? null : reply;
                return PlanDecision.accepted(true, intent, effectiveReply, plan);
            }
            error = planValidationError(errors);
        } catch (Exception e) {
            error = "plan 解析失败: " + e.getMessage();
        }
        return repairDecision(traceId, sink, parsed.toString(), error);
    }

    private String planValidationError(List<String> errors) {
        String error = "plan 校验失败: " + String.join("; ", errors);
        if (error.contains("未注册的工具")) {
            // 常见诱因：模型把 args 胶水进 tool 字符串。给出可用工具名和格式提示。
            StringBuilder names = new StringBuilder();
            registry.specs().forEach(spec -> names.append(names.isEmpty() ? "" : ", ")
                    .append(spec.name()));
            error += "；tool 字段只能填工具名本身（可用：" + names
                    + "），args 必须是独立的 JSON 对象字段，如 {\"tool\": \"get_ticker\", \"args\": {\"symbol\": \"BTC\"}}";
        }
        return error;
    }

    private PlanDecision repairDecision(String traceId, TraceSink sink, String output, String error) {
        sink.record(TraceEvent.plan(traceId, output, error));
        return new PlanDecision(true, null, null, null, error);
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
     * 输出 plan=null；tool 字段带注册工具名枚举，guided decoding 从生成层面禁止
     * 编造工具名或把 args 胶水进 tool 字符串。
     */
    private JsonNode plannerSchema() {
        try {
            ObjectNode schema = (ObjectNode) MAPPER.readTree("""
                    {"type": "object", "additionalProperties": false,
                     "properties": {
                       "in_domain": {"type": "boolean"},
                       "intent": {"type": "string"},
                       "reply": {"type": ["string", "null"]},
                       "query_requirements": {"type": ["object", "null"], "additionalProperties": false,
                         "properties": {
                           "requires_period_comparison": {"type": "boolean"}
                         },
                         "required": ["requires_period_comparison"]},
                       "plan": {"type": ["object", "null"], "additionalProperties": false,
                         "properties": {"nodes": {"type": "array", "items": {
                           "type": "object", "additionalProperties": false,
                           "properties": {
                             "id": {"type": "string"},
                             "tool": {"type": "string"},
                             "args": {"type": "object"},
                             "depends_on": {"type": "array", "items": {"type": "string"}},
                             "include_details": {"type": "boolean"}
                           },
                           "required": ["id", "tool", "args", "depends_on"]
                         }}},
                         "required": ["nodes"]}
                     },
                     "required": ["in_domain", "query_requirements", "plan"]}
                    """);
            ArrayNode toolEnum = MAPPER.createArrayNode();
            registry.specs().forEach(spec -> toolEnum.add(spec.name()));
            ((ObjectNode) schema.at("/properties/plan/properties/nodes/items/properties/tool"))
                    .set("enum", toolEnum);
            return schema;
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
