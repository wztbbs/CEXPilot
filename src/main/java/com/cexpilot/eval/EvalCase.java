package com.cexpilot.eval;

import java.util.List;

/**
 * 一条 Smoke Eval Case（三层 eval 结构中可机检的部分）：
 * - 第二层 Agent Behavior：expected_tools / forbidden_tools
 * - 证据完备性：required_evidence_keys（工具返回 facts 必须包含的字段）
 * - 第三层 Answer Quality 的机器部分：grounding（答案数字必须出自证据）
 *
 * @param setupQuestions 同对话中先问的问题（用于多轮追问 case），按顺序执行
 */
public record EvalCase(String id,
                       String category,
                       String question,
                       List<String> setupQuestions,
                       List<String> expectedTools,
                       List<String> forbiddenTools,
                       List<String> requiredEvidenceKeys) {
}
