package com.cexpilot.eval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class EvalRepository {

    private final JdbcTemplate jdbc;

    public EvalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveRun(String runId, String evalType, String category, int total, int passed,
                        Double toolAccuracy, int groundingPass, String model, String promptVersion) {
        jdbc.update("""
                        INSERT INTO eval_run
                            (run_id, eval_type, category, total, passed, tool_accuracy,
                             grounding_pass, model, prompt_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                runId, evalType, category, total, passed, toolAccuracy,
                groundingPass, model, promptVersion);
    }

    public void saveResult(String runId, String caseId, String question, boolean passed,
                           String expectedToolsJson, String actualToolsJson, String missingToolsJson,
                           String forbiddenHitJson, String evidenceMissingJson, Boolean groundingPassed,
                           String detailJson, String traceId) {
        jdbc.update("""
                        INSERT INTO eval_case_result
                            (run_id, case_id, question, passed, expected_tools, actual_tools,
                             missing_tools, forbidden_hit, evidence_missing, grounding_passed,
                             detail, trace_id)
                        VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON),
                                CAST(? AS JSON), CAST(? AS JSON), ?, CAST(? AS JSON), ?)
                        """,
                runId, caseId, question, passed, expectedToolsJson, actualToolsJson,
                missingToolsJson, forbiddenHitJson, evidenceMissingJson, groundingPassed,
                detailJson, traceId);
    }

    public Map<String, Object> findRun(String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM eval_run WHERE run_id = ?", runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findResults(String runId) {
        return jdbc.queryForList(
                "SELECT * FROM eval_case_result WHERE run_id = ? ORDER BY id", runId);
    }
}
