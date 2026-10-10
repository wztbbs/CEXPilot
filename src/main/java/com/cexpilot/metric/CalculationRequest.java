package com.cexpilot.metric;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 逻辑计划中的单个计算节点：引用指标或上游计算结果作为输入。
 */
public record CalculationRequest(String id, String operator, JsonNode input) {
}
