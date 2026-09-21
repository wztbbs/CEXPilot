package com.cexpilot.llm;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.exception.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.DefaultClientConnectionReuseStrategy;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议的 chat/completions 客户端，支持 function calling。
 * 兼容 OpenAI / DashScope 兼容模式 / DeepSeek 等实现。
 * 不作为组件自动注册：normal / flagship 两套实例由 LlmClientConfig 显式声明。
 *
 * HTTP 层用 Apache HttpClient5 连接池（keep-alive 复用，避免每次调用重新 TLS 握手；
 * 跨境链路下手握手一次约 0.5~1s）。idle 连接 30s 主动驱逐，防止复用到对端已静默
 * 关闭的半死连接。超时：连接 10s，等响应头 60s，数据包间隔 60s（挂死快速失败）。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final RestClient restClient;
    private final LlmConfig.ModelConfig config;
    private final double temperature;
    private final Long seed;
    private final String baseUrl;

    public OpenAiCompatibleClient(LlmConfig.ModelConfig config, double temperature, Long seed, boolean pooling) {
        this.config = config;
        this.temperature = temperature;
        this.seed = seed;

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(50);
        connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(10))
                // socket timeout = 两次数据包之间的最长间隔，对流式响应同样生效（chunk 间隙）
                .setSocketTimeout(Timeout.ofSeconds(60))
                .build());
        RequestConfig requestConfig = RequestConfig.custom()
                // 等响应头的最长时间：覆盖非流式调用的整体生成耗时
                .setResponseTimeout(Timeout.ofSeconds(60))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // pooling=false 只关掉连接复用，其余（超时、连接池上限、空闲驱逐）保持完全一致，
                // 便于 A/B 时把"每次调用的 TLS 握手开销"单独隔离出来。
                .setConnectionReuseStrategy(pooling
                        ? DefaultClientConnectionReuseStrategy.INSTANCE
                        : (request, response, context) -> false)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        String baseUrl = config.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                // Authorization 头只进请求，不进日志
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        return chat(messages, tools, null);
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, JsonNode responseFormat) {
        ObjectNode body = buildBody(messages, tools, responseFormat);

        long start = System.currentTimeMillis();
        log.info("LLM 请求 POST {}/chat/completions model={} 消息数={} body={}",
                baseUrl, config.getModel(), messages.size(), abbreviate(body.toString(), 2000));
        JsonNode response;
        try {
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            response = MAPPER.readTree(raw);
        } catch (RestClientResponseException e) {
            // HTTP 错误状态（如 401/429/451）：状态码 + 响应 body 必须留下来
            log.warn("LLM 请求失败 model={} {}ms 状态={} body={}",
                    config.getModel(), System.currentTimeMillis() - start,
                    e.getStatusCode(), abbreviate(e.getResponseBodyAsString(), 2000));
            throw new LlmException("LLM 调用失败: HTTP " + e.getStatusCode() + " " + e.getStatusText(), e);
        } catch (Exception e) {
            log.warn("LLM 请求异常 model={} {}ms: {}",
                    config.getModel(), System.currentTimeMillis() - start, e.getMessage());
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }

        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            log.warn("LLM 返回缺少 choices model={} body={}",
                    config.getModel(), abbreviate(response.toString(), 2000));
            throw new LlmException("LLM 返回缺少 choices: " + response);
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isTextual() ? message.path("content").asText() : null;

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode tc : toolCallsNode) {
                JsonNode function = tc.path("function");
                toolCalls.add(new ToolCall(
                        tc.path("id").asText(""),
                        function.path("name").asText(""),
                        function.path("arguments").isTextual()
                                ? function.path("arguments").asText()
                                : function.path("arguments").toString()));
            }
        }

        JsonNode usage = response.path("usage");
        Integer promptTokens = usage.path("prompt_tokens").isInt() ? usage.path("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.path("completion_tokens").isInt() ? usage.path("completion_tokens").asInt() : null;
        // prompt 缓存命中量：区分上游慢是"冷缓存全量 prefill"还是"纯排队"
        Integer cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").isInt()
                ? usage.path("prompt_tokens_details").path("cached_tokens").asInt() : null;

        log.info("LLM 响应 {}ms model={} content={} promptTokens={} completionTokens={} cachedTokens={}",
                System.currentTimeMillis() - start, config.getModel(),
                abbreviate(content, 500), promptTokens, completionTokens, cachedTokens);
        return new ChatResponse(content, toolCalls, promptTokens, completionTokens);
    }

    /**
     * 流式回答（OpenAI SSE）：逐 chunk 回调增量文本，聚合为完整响应返回。
     * stream_options.include_usage 让最后一个 chunk 携带 token 用量；客户端断开等
     * 回调异常会向上传播中断读取，由调用方按失败处理。
     */
    @Override
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                   Consumer<String> onDelta) {
        ObjectNode body = buildBody(messages, tools, null);
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);

        long start = System.currentTimeMillis();
        log.info("LLM 流式请求 POST {}/chat/completions model={} 消息数={}",
                baseUrl, config.getModel(), messages.size());
        StringBuilder content = new StringBuilder();
        Integer[] tokens = new Integer[2]; // [promptTokens, completionTokens]
        try {
            restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            String errorBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new LlmException("LLM 流式调用失败: HTTP " + response.getStatusCode()
                                    + " " + abbreviate(errorBody, 500));
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String payload = line.substring(5).trim();
                                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                                    continue;
                                }
                                JsonNode chunk = MAPPER.readTree(payload);
                                JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
                                if (delta.isTextual() && !delta.asText().isEmpty()) {
                                    onDelta.accept(delta.asText());
                                    content.append(delta.asText());
                                }
                                JsonNode usage = chunk.path("usage");
                                if (usage.isObject() && usage.path("prompt_tokens").isInt()) {
                                    tokens[0] = usage.path("prompt_tokens").asInt();
                                    tokens[1] = usage.path("completion_tokens").asInt();
                                }
                            }
                        }
                        return null;
                    });
        } catch (LlmException e) {
            log.warn("LLM 流式调用失败 model={} {}ms: {}",
                    config.getModel(), System.currentTimeMillis() - start, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("LLM 流式调用异常 model={} {}ms: {}",
                    config.getModel(), System.currentTimeMillis() - start, e.getMessage());
            throw new LlmException("LLM 流式调用失败: " + e.getMessage(), e);
        }

        log.info("LLM 流式响应 {}ms model={} promptTokens={} completionTokens={}",
                System.currentTimeMillis() - start, config.getModel(), tokens[0], tokens[1]);
        return new ChatResponse(content.toString(), List.of(), tokens[0], tokens[1]);
    }

    private ObjectNode buildBody(List<ChatMessage> messages, List<ToolSpec> tools, JsonNode responseFormat) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", temperature);
        if (seed != null) {
            body.put("seed", seed);
        }
        if (responseFormat != null) {
            body.set("response_format", responseFormat);
        }
        if (config.getEnableThinking() != null) {
            // Qwen3 混合模型的思考开关：思考 token 计入 completion 且逐字生成，
            // 低延迟场景（规划 / 模板化回答）应关闭
            body.put("enable_thinking", config.getEnableThinking());
        }
        body.set("messages", serializeMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", serializeTools(tools));
        }
        return body;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private ArrayNode serializeMessages(List<ChatMessage> messages) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ChatMessage msg : messages) {
            ObjectNode node = arr.addObject();
            node.put("role", msg.role());
            if (msg.content() != null) {
                node.put("content", msg.content());
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsNode = node.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsNode.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode function = tcNode.putObject("function");
                    function.put("name", tc.name());
                    function.put("arguments", tc.argumentsJson());
                }
            }
            if (msg.toolCallId() != null) {
                node.put("tool_call_id", msg.toolCallId());
            }
            if (msg.name() != null) {
                node.put("name", msg.name());
            }
        }
        return arr;
    }

    private ArrayNode serializeTools(List<ToolSpec> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ToolSpec spec : tools) {
            ObjectNode toolNode = arr.addObject();
            toolNode.put("type", "function");
            ObjectNode function = toolNode.putObject("function");
            function.put("name", spec.name());
            function.put("description", spec.description());
            function.set("parameters", spec.inputSchema());
        }
        return arr;
    }
}
