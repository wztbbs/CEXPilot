package com.cexpilot.llm;

import com.cexpilot.config.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 只检查请求组装，不发送 HTTP 请求。 */
class LlmRequestParametersTest {
    @Test void auditParametersMatchWireBodyAndExcludeCredentials() throws Exception {
        var config = new LlmConfig.ModelConfig();
        config.setModel("test-model");
        config.setApiKey("test-secret-must-not-appear");
        config.setEnableThinking(false);
        var client = new OpenAiCompatibleClient(config, 0.2, 42L, false);
        JsonNode format = new ObjectMapper().readTree("{\"type\":\"json_schema\",\"json_schema\":{\"schema\":{\"type\":\"object\"}}}");
        var parameters = client.requestParameters(format);
        assertEquals("test-model", parameters.path("model").asText());
        assertEquals(0.2, parameters.path("temperature").asDouble());
        assertEquals(42L, parameters.path("seed").asLong());
        assertFalse(parameters.path("enable_thinking").asBoolean());
        assertEquals(format, parameters.get("response_format"));
        assertFalse(parameters.toString().contains("test-secret"));
        assertEquals(java.util.Set.of("model", "temperature", "seed", "enable_thinking", "response_format"),
                new ObjectMapper().convertValue(parameters, java.util.Map.class).keySet());
        var build = OpenAiCompatibleClient.class.getDeclaredMethod("buildBody", List.class, List.class, JsonNode.class);
        build.setAccessible(true);
        var body = (ObjectNode) build.invoke(client, List.of(ChatMessage.user("test")), null, format);
        body.remove("messages");
        assertEquals(parameters, body);
    }

    @Test void omittedOptionsStayOmitted() {
        var client = new OpenAiCompatibleClient(new LlmConfig.ModelConfig(), 0, null, false);
        var parameters = client.requestParameters(null);
        assertFalse(parameters.has("response_format"));
        assertFalse(parameters.has("seed"));
        assertFalse(parameters.has("enable_thinking"));
    }
}
