package com.cexpilot.eval;

import com.cexpilot.api.AskResponse;
import com.cexpilot.api.AskService;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.trace.TraceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Smoke Eval 执行器：
 * 逐条跑 case → 从 trace 回放实际工具调用 → 比对 expected/forbidden →
 * 检查证据字段完备性 → grounding 校验 → 结果落库。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvalCaseLoader caseLoader;
    private final AskService askService;
    private final TraceRepository traceRepository;
    private final EvalRepository evalRepository;
    private final DagRuntime runtime;
    private final LlmConfig llmConfig;

    public EvalService(EvalCaseLoader caseLoader, AskService askService,
                       TraceRepository traceRepository, EvalRepository evalRepository,
                       DagRuntime runtime, LlmConfig llmConfig) {
        this.caseLoader = caseLoader;
        this.askService = askService;
        this.traceRepository = traceRepository;
        this.evalRepository = evalRepository;
        this.runtime = runtime;
        this.llmConfig = llmConfig;
    }

    public Map<String, Object> run(String category) {
        List<EvalCase> cases = caseLoader.load(category);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("没有找到 eval case，category=" + category);
        }
        String runId = UUID.randomUUID().toString();
        int passed = 0;
        int toolMatch = 0;
        int groundingPass = 0;

        for (EvalCase evalCase : cases) {
            boolean ok = runCase(runId, evalCase);
            if (ok) {
                passed++;
            }
            Map<String, Object> lastResult = lastResult(runId);
            if (Boolean.TRUE.equals(lastResult.get("tool_matched"))) {
                toolMatch++;
            }
            if (Boolean.TRUE.equals(lastResult.get("grounding_passed"))) {
                groundingPass++;
            }
        }

        double toolAccuracy = cases.isEmpty() ? 0 : (double) toolMatch / cases.size();
        evalRepository.saveRun(runId, "smoke", category, cases.size(), passed,
                toolAccuracy, groundingPass, llmConfig.getNormal().getModel(), runtime.promptVersion());

        return Map.of(
                "run_id", runId,
                "category", category == null ? "all" : category,
                "total", cases.size(),
                "passed", passed,
                "tool_accuracy", toolAccuracy,
                "grounding_pass", groundingPass);
    }

    private boolean runCase(String runId, EvalCase evalCase) {
        log.info("[eval] case {}: {}", evalCase.id(), evalCase.question());
        try {
            String conversationId = null;
            AskResponse response = null;
            for (String setupQuestion : evalCase.setupQuestions()) {
                response = askService.ask(conversationId, setupQuestion);
                conversationId = response.conversationId();
            }
            response = askService.ask(conversationId, evalCase.question());

            Set<String> actualTools = actualTools(response.traceId());
            List<String> missing = evalCase.expectedTools().stream()
                    .filter(t -> !actualTools.contains(t)).toList();
            List<String> forbiddenHit = evalCase.forbiddenTools().stream()
                    .filter(actualTools::contains).toList();
            List<String> evidenceMissing = missingEvidenceKeys(response.evidence(), evalCase.requiredEvidenceKeys());
            GroundingChecker.GroundingResult grounding = GroundingChecker.check(
                    response.answer(), response.evidence(), evalCase.question());

            boolean toolMatched = missing.isEmpty() && forbiddenHit.isEmpty();
            boolean passed = toolMatched && evidenceMissing.isEmpty() && grounding.passed();

            ObjectNode detail = MAPPER.createObjectNode();
            detail.put("tool_matched", toolMatched);
            detail.put("answer", abbreviate(response.answer(), 500));
            if (!grounding.ungrounded().isEmpty()) {
                detail.putPOJO("ungrounded_numbers", grounding.ungrounded());
            }

            evalRepository.saveResult(runId, evalCase.id(), evalCase.question(), passed,
                    toJson(evalCase.expectedTools()), toJson(new ArrayList<>(actualTools)),
                    toJson(missing), toJson(forbiddenHit), toJson(evidenceMissing),
                    grounding.passed(), detail.toString(), response.traceId());
            return passed;
        } catch (Exception e) {
            log.warn("[eval] case {} 执行异常: {}", evalCase.id(), e.getMessage());
            evalRepository.saveResult(runId, evalCase.id(), evalCase.question(), false,
                    toJson(evalCase.expectedTools()), "[]", "[]", "[]", "[]",
                    null, "{\"error\": \"" + abbreviate(e.getMessage(), 200) + "\"}", null);
            return false;
        }
    }

    private Set<String> actualTools(String traceId) {
        Set<String> tools = new LinkedHashSet<>();
        for (Map<String, Object> event : traceRepository.findEvents(traceId)) {
            if ("TOOL_CALL".equals(event.get("event_type"))) {
                tools.add(String.valueOf(event.get("name")));
            }
        }
        return tools;
    }

    /** required_evidence_keys 中未出现在任一工具返回 facts 里的字段。 */
    private List<String> missingEvidenceKeys(JsonNode evidence, List<String> requiredKeys) {
        List<String> missing = new ArrayList<>();
        for (String key : requiredKeys) {
            if (!containsKey(evidence, key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    private boolean containsKey(JsonNode node, String key) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            if (node.has(key)) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsKey(child, key)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsKey(child, key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> lastResult(String runId) {
        List<Map<String, Object>> results = evalRepository.findResults(runId);
        return results.isEmpty() ? Map.of() : results.get(results.size() - 1);
    }

    private static String toJson(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
