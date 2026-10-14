package com.cexpilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlannerModelConfigTest {
    @Test void 第二模型默认复用第一模型连接且支持独立覆盖() throws Exception {
        var environment = new StandardEnvironment();
        // 不读取机器真实凭据，测试使用独立环境与固定假参数。
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("app",new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("models",Map.of(
                "LLM_NORMAL_API_KEY","test-key-one", "LLM_NORMAL_BASE_URL","http://model-one.invalid/v1",
                "LLM_FLAGSHIP_MODEL","model-two")));
        assertTrue("test-key-one".equals(environment.getProperty("llm.flagship.api-key")), "模型 2 应继承测试 Key");
        assertEquals("http://model-one.invalid/v1",environment.getProperty("llm.flagship.base-url"));
        assertEquals("model-two",environment.getProperty("llm.flagship.model"));
        environment.getPropertySources().addFirst(new MapPropertySource("repair",Map.of(
                "LLM_FLAGSHIP_API_KEY","test-key-two", "LLM_FLAGSHIP_BASE_URL","http://model-two.invalid/v1")));
        assertTrue("test-key-two".equals(environment.getProperty("llm.flagship.api-key")), "模型 2 应使用独立测试 Key");
        assertEquals("http://model-two.invalid/v1",environment.getProperty("llm.flagship.base-url"));
    }
}
