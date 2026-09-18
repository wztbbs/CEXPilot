package com.cexpilot.llm;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.exception.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容协议的 chat/completions 客户端，支持 function calling。
 * 兼容 OpenAI / DashScope 兼容模式 / DeepSeek 等实现。
 * 不作为组件自动注册：normal / flagship 两套实例由 LlmClientConfig 显式声明。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final LlmConfig.ModelConfig config;
    private final double temperature;

    public OpenAiCompatibleClient(LlmConfig.ModelConfig config, double temperature) {
        this.config = config;
        this.temperature = temperature;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(300));
        String baseUrl = config.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", temperature);
        body.set("messages", serializeMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", serializeTools(tools));
        }

        JsonNode response;
        try {
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            response = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }

        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
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

        return new ChatResponse(content, toolCalls, promptTokens, completionTokens);
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
