package com.cexpilot.metric;

/**
 * 品种规格；settle 在 LLM 输出中可省略，由编译器按产品默认补全。
 */
public record InstrumentSpec(String marketType, String base, String quote, String settle) {
}
