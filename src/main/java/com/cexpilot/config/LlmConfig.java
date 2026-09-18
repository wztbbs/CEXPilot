package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 接入配置。normal（普通模型）与 flagship（旗舰模型）两套并存，
 * 各自有独立的 baseUrl/apiKey/model/单价；temperature、步数上限等为全局共享。
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    private ModelConfig normal = new ModelConfig();
    private ModelConfig flagship = new ModelConfig();
    private double temperature = 0.1;
    private int maxSteps = 4;
    private int maxToolCalls = 8;

    public ModelConfig getNormal() {
        return normal;
    }

    public void setNormal(ModelConfig normal) {
        this.normal = normal;
    }

    public ModelConfig getFlagship() {
        return flagship;
    }

    public void setFlagship(ModelConfig flagship) {
        this.flagship = flagship;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    /** 一套 LLM 接入的连接与计费配置。 */
    public static class ModelConfig {

        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String model = "";
        private double priceInputPer1k = 0;
        private double priceOutputPer1k = 0;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getPriceInputPer1k() {
            return priceInputPer1k;
        }

        public void setPriceInputPer1k(double priceInputPer1k) {
            this.priceInputPer1k = priceInputPer1k;
        }

        public double getPriceOutputPer1k() {
            return priceOutputPer1k;
        }

        public void setPriceOutputPer1k(double priceOutputPer1k) {
            this.priceOutputPer1k = priceOutputPer1k;
        }
    }
}
