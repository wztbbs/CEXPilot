package com.cexpilot.config;

import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.OpenAiCompatibleClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 注册两套并存的 LLM 客户端：normal（普通模型）与 flagship（旗舰模型）。
 * normal 标为 @Primary，现有按类型注入 LlmClient 的流程默认走普通模型；
 * 需要旗舰模型的场景按 bean 名 flagshipLlmClient 注入。
 */
@Configuration
public class LlmClientConfig {

    public static final String NORMAL_LLM_CLIENT = "normalLlmClient";
    public static final String FLAGSHIP_LLM_CLIENT = "flagshipLlmClient";

    @Bean(NORMAL_LLM_CLIENT)
    @Primary
    public LlmClient normalLlmClient(LlmConfig config) {
        return new OpenAiCompatibleClient(config.getNormal(), config.getTemperature());
    }

    @Bean(FLAGSHIP_LLM_CLIENT)
    public LlmClient flagshipLlmClient(LlmConfig config) {
        return new OpenAiCompatibleClient(config.getFlagship(), config.getTemperature());
    }
}
