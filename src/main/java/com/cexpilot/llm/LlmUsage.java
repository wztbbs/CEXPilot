package com.cexpilot.llm;

import com.cexpilot.config.LlmConfig;
import java.util.List;

/** 每次调用使用的模型及其计费口径；未收到 usage 时不能推断为零费用。 */
public record LlmUsage(String modelRole, String model, Integer promptTokens, Integer completionTokens,
                       Double estimatedCost) {
    public static LlmUsage of(String role, LlmConfig.ModelConfig config, ChatResponse response) {
        Integer input = response == null ? null : response.promptTokens();
        Integer output = response == null ? null : response.completionTokens();
        Double cost = input == null || output == null ? null
                : input / 1000.0 * config.getPriceInputPer1k() + output / 1000.0 * config.getPriceOutputPer1k();
        return new LlmUsage(role, config.getModel(), input, output, cost);
    }

    public static Double totalCost(List<LlmUsage> usages) {
        if (usages == null || usages.isEmpty() || usages.stream().anyMatch(u -> u.estimatedCost() == null)) return null;
        return usages.stream().mapToDouble(LlmUsage::estimatedCost).sum();
    }

    /** 失败时只汇总已收到的 usage；未知调用的 token 不伪造为已核实的零。 */
    public static int knownPromptTokens(List<LlmUsage> usages) {
        return usages.stream().filter(u -> u.promptTokens() != null).mapToInt(LlmUsage::promptTokens).sum();
    }

    public static int knownCompletionTokens(List<LlmUsage> usages) {
        return usages.stream().filter(u -> u.completionTokens() != null).mapToInt(LlmUsage::completionTokens).sum();
    }
}
