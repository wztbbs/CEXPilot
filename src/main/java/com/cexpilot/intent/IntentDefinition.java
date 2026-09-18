package com.cexpilot.intent;

import java.util.List;

/**
 * 一个意图的定义（classpath:intents/*.yml）：
 * 命中的意图决定本次 Execution 允许使用的工具子集与工具调用步数上限。
 */
public record IntentDefinition(String name,
                               String description,
                               List<String> allowedTools,
                               Integer maxToolCalls) {
}
