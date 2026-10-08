package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.metric.MetricCatalog;
import com.cexpilot.metric.MetricPlanCompiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.LlmJson;
import com.cexpilot.llm.LlmTraceSerializer;
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

/** 单次 LLM 生成紧凑指标计划；确定性编译为 Tool DAG，再校验和有限次数修复。 */
@Component
public class DagPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROMPT_NAME = "dag_planner";
    private static final String TRACE_NAME = "dag_planner";
    /** 透传 reply 的长度上限：合法追问/边界话术都很短，超长基本是把推理过程倒进了 reply。 */
    private static final int REPLY_MAX_LENGTH = 100;

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final LlmConfig llmConfig;
    private final DagConfig dagConfig;
    private final PromptStore prompts;
    private final PlanValidator validator;
    private final MetricCatalog catalog;
    private final MetricPlanCompiler compiler;

    public DagPlanner(LlmClient llm, ToolRegistry registry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator, com.cexpilot.metric.MetricProviderRegistry metricProviders) {
        this(llm, registry, llmConfig, dagConfig, prompts, validator,
                new MetricCatalog(new DefaultResourceLoader()), metricProviders);
    }

    @Autowired
    public DagPlanner(LlmClient llm, ToolRegistry registry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator, MetricCatalog catalog,
                      com.cexpilot.metric.MetricProviderRegistry metricProviders) {
        this.catalog = catalog;
        this.compiler = new MetricPlanCompiler(catalog, registry, metricProviders);
        this.llm = llm;
        this.registry = registry;
        this.llmConfig = llmConfig;
        this.dagConfig = dagConfig;
        this.prompts = prompts;
        this.validator = validator;
    }

    /**
     * 规划结果。inDomain=false 时 reply 为边界话术；inDomain=true 且 plan 为空表示
     * 模型判断工具不足以回答（reply 说明缺口）或 repair 耗尽（lastError 非空）。
     */
    public record PlanOutcome(boolean inDomain, String reply,
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

            PlanDecision decision = evaluateResponse(response.content(), maxToolCalls, traceId, sink);
            if (decision.error() == null) {
                return decision.toOutcome(promptTokens, completionTokens);
            }
            lastError = decision.error();
            appendRepair(messages, response.content(), lastError);
        }
        // repair 耗尽：runtime 直接返回固定失败话术，禁止继续生成无事实答案。
        return new PlanOutcome(true, null,
                Optional.empty(), promptTokens, completionTokens, lastError);
    }

    /** 单轮判断不负责 token 累计；error 非空时由 plan 统一追加修复消息并重试。 */
    private record PlanDecision(boolean inDomain, String reply, DagPlan plan, String error) {
        private static PlanDecision accepted(boolean inDomain, String reply, DagPlan plan) {
            return new PlanDecision(inDomain, reply, plan, null);
        }

        private PlanOutcome toOutcome(int promptTokens, int completionTokens) {
            return new PlanOutcome(inDomain, reply, Optional.ofNullable(plan),
                    promptTokens, completionTokens, error);
        }
    }

    private PlanDecision evaluateResponse(String content, int maxToolCalls,
                                          String traceId, TraceSink sink) {
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
        return evaluateInDomainResponse(parsed, maxToolCalls, traceId, sink);
    }

    private PlanDecision evaluateInDomainResponse(JsonNode parsed, int maxToolCalls,
                                                  String traceId, TraceSink sink) {
        String reply = textOrNull(parsed.path("reply"));
        JsonNode planNode = parsed.path("plan");
        if (!planNode.isObject()) {
            return evaluateReplyWithoutPlan(parsed, reply, traceId, sink);
        }
        return validatePlan(parsed, reply, maxToolCalls, traceId, sink);
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
                                      String traceId, TraceSink sink) {
        String error;
        try {
            DagPlan plan = compiler.compile(parsed.path("plan"), Math.min(dagConfig.getMaxNodes(), maxToolCalls));
            if (plan.nodes().isEmpty() && reply != null && !reply.isBlank()) {
                return evaluateReplyWithoutPlan(parsed, reply, traceId, sink);
            }
            List<String> errors = validator.validate(plan, compiler.allowedTools(), maxToolCalls);
            if (errors.isEmpty()) {
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                sink.record(new TraceEvent(traceId, "PLAN_COMPILED", "metric_compiler",
                        MAPPER.createObjectNode().put("catalog_version", catalog.version()).toString(),
                        plan.toJson().toString(), null, null, null, null, null, null));
                // plan 已合法时，超长 reply 直接丢弃，不再为 reply 重试。
                String effectiveReply = isReasoningDump(reply) ? null : reply;
                return PlanDecision.accepted(true, effectiveReply, plan);
            }
            error = planValidationError(compiler.logicalErrors(plan, errors));
        } catch (Exception e) {
            error = "plan 解析失败: " + e.getMessage();
        }
        return repairDecision(traceId, sink, parsed.toString(), error);
    }

    private String planValidationError(List<String> errors) {
        return "plan 校验失败: " + String.join("; ", errors)
                + "；仅使用指标目录与算子，引用格式为 {{指标组.交易所.value}} / {{计算ID.value}}，不要输出底层 Tool 节点";
    }

    private PlanDecision repairDecision(String traceId, TraceSink sink, String output, String error) {
        sink.record(TraceEvent.plan(traceId, output, error));
        return new PlanDecision(true, null, null, error);
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
            ((ObjectNode) schema.at("/properties/plan/properties/calculations/items/properties/operator"))
                    .set("enum", MAPPER.valueToTree(catalog.operators().stream().sorted()
                            .filter(name -> registry.get(name) instanceof com.cexpilot.calculation.CalculationTool).toList()));
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
                                 JsonNode responseFormat) {
        long start = System.currentTimeMillis();
        String input = eventInput(attempt, messages, responseFormat);
        try {
            ChatResponse response = llm.chat(messages, null, responseFormat);
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    input, LlmTraceSerializer.responseToJson(response),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(),
                    response.ttftMs(), response.cachedTokens(), null));
            return response;
        } catch (Exception e) {
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    input, null,
                    System.currentTimeMillis() - start, null, null, null, null, e.getMessage()));
            throw e;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private String eventInput(int attempt, List<ChatMessage> messages, JsonNode responseFormat) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("stage", TRACE_NAME);
        node.put("attempt", attempt);
        node.put("message_count", messages.size());
        node.set("messages", LlmTraceSerializer.messagesToArray(messages));
        llm.requestParameters(responseFormat).fields().forEachRemaining(field -> node.set(field.getKey(), field.getValue()));
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
