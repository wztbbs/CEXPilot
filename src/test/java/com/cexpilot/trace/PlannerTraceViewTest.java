package com.cexpilot.trace;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class PlannerTraceViewTest {
    public static Map<String,Object> event(int seq,String type,String input,String output,String error) {
        var row = new LinkedHashMap<String,Object>();
        row.put("seq",seq); row.put("event_type",type); row.put("name","dag_planner");
        row.put("input_json",input); row.put("output_json",output); row.put("error",error);
        row.put("duration_ms",100); row.put("prompt_tokens",10); row.put("completion_tokens",5);
        return row;
    }
    public static List<Map<String,Object>> repaired() {
        return List.of(
                event(1,"LLM_CALL","{\"attempt\":1,\"model\":\"model-one\",\"model_role\":\"normal\"}","{\"content\":\"FIRST_BAD_OUTPUT\",\"estimated_cost\":0.02}",null),
                event(2,"PLAN",null,"{}","invalid JSON"),
                event(3,"PLANNER_ATTEMPT","{\"attempt\":1,\"model\":\"model-one\",\"model_role\":\"normal\"}",
                        "{\"call_status\":\"SUCCEEDED\",\"validation_status\":\"FAILED\",\"result\":\"REPAIR_REQUIRED\",\"validation_error\":\"invalid JSON\"}","invalid JSON"),
                event(4,"LLM_CALL","{\"attempt\":2,\"model\":\"model-two\",\"model_role\":\"flagship\"}","{\"content\":\"SECOND_RAW_OUTPUT\",\"estimated_cost\":0.2}",null),
                event(5,"PLAN_COMPILED",null,"{\"nodes\":[{\"id\":\"PROVISIONAL\"}]}",null),
                event(6,"PLANNER_ATTEMPT","{\"attempt\":2,\"model\":\"model-two\",\"model_role\":\"flagship\",\"retry_reason\":\"invalid JSON\"}",
                        "{\"call_status\":\"SUCCEEDED\",\"validation_status\":\"PASSED\",\"result\":\"EXECUTABLE\",\"logical_plan\":{\"metrics\":[{\"id\":\"FINAL_ONLY\"}],\"calculations\":[]},\"compiled_plan\":{\"nodes\":[{\"id\":\"FINAL_NODE\"}]}}",null));
    }

    @Test void 每次尝试模型与校验结果明确且选择终态中的真实计划() {
        var view=PlannerTraceView.from(repaired());
        assertEquals(2,view.attempts().size());
        var first=view.attempts().get(0);var second=view.attempts().get(1);
        assertEquals("model-one",first.get("model")); assertEquals("model-two",second.get("model"));
        assertEquals("SUCCEEDED",first.get("call_status")); assertEquals("FAILED",first.get("validation_status"));
        assertEquals("PASSED",second.get("validation_status")); assertEquals("invalid JSON",second.get("retry_reason"));
        assertEquals(4,second.get("llm_call_seq")); assertEquals(6,second.get("result_seq"));
        assertEquals("FINAL_ONLY",view.finalPlan().at("/metrics/0/id").asText());
        assertEquals("FINAL_NODE",view.finalCompiledPlan().at("/nodes/0/id").asText());
    }

    @Test void 编译通过但历史绑定失败不能展示成成功计划() {
        var events=new ArrayList<>(repaired().subList(0,5));
        events.add(event(6,"PLANNER_ATTEMPT","{\"attempt\":2}","{\"call_status\":\"SUCCEEDED\",\"validation_status\":\"FAILED\",\"result\":\"NEEDS_CLARIFICATION\",\"validation_error\":\"历史输入不可恢复\"}","历史输入不可恢复"));
        var view=PlannerTraceView.from(events);
        assertNull(view.finalPlan()); assertNull(view.finalCompiledPlan());
        assertEquals("FAILED",view.attempts().get(1).get("validation_status"));
    }

    @Test void 调用失败与正常澄清分别记录未运行和不适用的校验状态() {
        for(String status:List.of("FAILED","SUCCEEDED")) {
            boolean failed=status.equals("FAILED");
            var view=PlannerTraceView.from(List.of(
                    event(1,"LLM_CALL","{\"attempt\":1,\"model\":\"one\"}",failed?null:"{}",failed?"timeout":null),
                    event(2,"PLANNER_ATTEMPT","{\"attempt\":1}",
                            "{\"call_status\":\""+status+"\",\"validation_status\":\""+(failed?"NOT_RUN":"NOT_APPLICABLE")+"\",\"result\":\""+(failed?"CALL_FAILED":"NEEDS_CLARIFICATION")+"\"}",failed?"timeout":null)));
            assertNull(view.finalPlan()); assertEquals(failed?"NOT_RUN":"NOT_APPLICABLE",view.attempts().get(0).get("validation_status"));
        }
    }

    @Test void 旧记录不编造模型或成功状态且损坏JSON不让接口报错() {
        var rows=List.of(event(1,"LLM_CALL"," ","{}",null),event(2,"PLAN","broken JSON",null,"旧校验错误"));
        var view=PlannerTraceView.from(rows);
        assertNull(view.attempts().get(0).get("model"));assertEquals("FAILED",view.attempts().get(0).get("validation_status"));
        assertNull(view.finalPlan());
        assertEquals("UNKNOWN",PlannerTraceView.from(List.of(rows.get(0))).attempts().get(0).get("validation_status"));
    }

    @Test void 按seq关联且不修改完整原始事件() {
        var rows=new ArrayList<>(repaired());var before=new ArrayList<>(rows);Collections.reverse(rows);
        var view=PlannerTraceView.from(rows);
        assertEquals("model-two",view.attempts().get(1).get("model"));
        assertEquals("FINAL_ONLY",view.finalPlan().at("/metrics/0/id").asText());
        assertEquals(6,rows.get(0).get("seq"));
        assertSame(before.get(0),rows.get(rows.size()-1));
        assertTrue(String.valueOf(before.get(0).get("output_json")).contains("FIRST_BAD_OUTPUT"));
    }
}
