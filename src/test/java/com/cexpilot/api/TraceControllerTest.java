package com.cexpilot.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceControllerTest {

    @Test
    void 两个明细接口保留原事件并返回不同模型尝试与最终计划() {
        var repo=org.mockito.Mockito.mock(com.cexpilot.trace.TraceRepository.class);
        var events=com.cexpilot.trace.PlannerTraceViewTest.repaired();
        org.mockito.Mockito.when(repo.findTrace("t")).thenReturn(java.util.Map.of("trace_id","t","model","model-one"));
        org.mockito.Mockito.when(repo.findEvents("t")).thenReturn(events);
        var controller=new TraceController(repo);
        for(var response:java.util.List.of(controller.trace("t"),controller.traceDetail("t"))) {
            var body=(java.util.Map<?,?>)response.getBody();
            org.junit.jupiter.api.Assertions.assertSame(events,body.get("events"));
            var attempts=(java.util.List<?>)body.get("planner_attempts");
            org.junit.jupiter.api.Assertions.assertEquals("model-one",((java.util.Map<?,?>)attempts.get(0)).get("model"));
            org.junit.jupiter.api.Assertions.assertEquals("model-two",((java.util.Map<?,?>)attempts.get(1)).get("model"));
            org.junit.jupiter.api.Assertions.assertEquals("FINAL_ONLY",((com.fasterxml.jackson.databind.JsonNode)body.get("final_plan")).at("/metrics/0/id").asText());
        }
    }

    @Test
    void 批量接口在剥离大字段前保留可供评测的两次模型摘要() {
        var repo=org.mockito.Mockito.mock(com.cexpilot.trace.TraceRepository.class);
        var events=com.cexpilot.trace.PlannerTraceViewTest.repaired();
        var trace=new java.util.LinkedHashMap<String,Object>();trace.put("trace_id","t");
        org.mockito.Mockito.when(repo.dbNow()).thenReturn(java.sql.Timestamp.valueOf("2026-10-14 12:00:00"));
        org.mockito.Mockito.when(repo.findTracesBetween(240,0)).thenReturn(java.util.List.of(trace));
        org.mockito.Mockito.when(repo.findEventsByTraceIds(java.util.List.of("t"))).thenReturn(java.util.Map.of("t",events));
        org.mockito.Mockito.when(repo.findFeedbackByTraceIds(java.util.List.of("t"))).thenReturn(java.util.Map.of());
        new TraceController(repo).traces(4,0);
        var attempts=(java.util.List<?>)trace.get("planner_attempts");
        org.junit.jupiter.api.Assertions.assertEquals(2,attempts.size());
        org.junit.jupiter.api.Assertions.assertEquals("PASSED",((java.util.Map<?,?>)attempts.get(1)).get("validation_status"));
        for(Object item:(java.util.List<?>)trace.get("events")) {
            org.junit.jupiter.api.Assertions.assertFalse(((java.util.Map<?,?>)item).containsKey("input_json"));
            org.junit.jupiter.api.Assertions.assertFalse(((java.util.Map<?,?>)item).containsKey("output_json"));
        }
        org.junit.jupiter.api.Assertions.assertTrue(events.get(0).containsKey("input_json"));
    }

    @Test
    void acceptValidWindows() {
        assertNull(TraceController.validateWindow(4, 0));
        assertNull(TraceController.validateWindow(12, 8));
        assertNull(TraceController.validateWindow(24, 0));
        assertNull(TraceController.validateWindow(0.5, 0));
    }

    @Test
    void rejectBeginNotAfterEnd() {
        assertNotNull(TraceController.validateWindow(0, 0));
        assertNotNull(TraceController.validateWindow(4, 4));
        assertNotNull(TraceController.validateWindow(2, 8));
    }

    @Test
    void rejectWindowLongerThan24Hours() {
        assertNotNull(TraceController.validateWindow(25, 0));
        assertNotNull(TraceController.validateWindow(30, 5));
    }

    @Test
    void rejectNegativeOrAbsurdHours() {
        assertNotNull(TraceController.validateWindow(4, -1));
        assertNotNull(TraceController.validateWindow(24 * 367, 24 * 366));
        assertNotNull(TraceController.validateWindow(Double.NaN, 0));
    }
}
