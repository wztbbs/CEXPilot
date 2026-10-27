package com.cexpilot.monitor;

import com.cexpilot.agent.*;
import com.cexpilot.config.DagConfig;
import com.cexpilot.llm.*;
import com.cexpilot.metric.MetricCatalog;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MonitorPlanner {
    public static final String PROMPT="monitor_planner";
    public record Outcome(String status,String reply,JsonNode candidate,List<LlmUsage> usages) {}
    public static class Failure extends RuntimeException {
        public final List<LlmUsage> usages;
        Failure(RuntimeException cause,List<LlmUsage> usages) { super(cause); this.usages=List.copyOf(usages); }
    }
    private final PlanningLlm llm;
    private final PromptStore prompts;
    private final MetricCatalog catalog;
    private final ToolRegistry registry;
    private final MonitorPlanValidator validator;
    private final DagConfig config;
    public MonitorPlanner(PlanningLlm llm,PromptStore prompts,MetricCatalog catalog,ToolRegistry registry,
                          MonitorPlanValidator validator,DagConfig config) {
        this.llm=llm; this.prompts=prompts; this.catalog=catalog; this.registry=registry; this.validator=validator; this.config=config;
    }
    public Outcome plan(String question,JsonNode context,String traceId,TraceSink sink) {
        var messages=new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(prompts.render(PROMPT,Map.of("concepts",catalog.concepts(),
                "metrics",catalog.describeMetrics(),"operators",catalog.describeOperators(registry)))));
        var user=new ObjectMapper().createObjectNode().put("question",question); user.set("context",context);
        messages.add(ChatMessage.user(user.toString()));
        var usages=new ArrayList<LlmUsage>();
        try {
            for(int attempt=0;attempt<=Math.min(config.getPlannerMaxRetries(),2);attempt++) {
                var response=llm.call("monitor_planner",PROMPT,messages,traceId,sink,usages);
                try {
                    if(response.hasToolCalls()) throw new IllegalArgumentException("监控规划不能调用工具");
                    JsonNode envelope=PlanningJson.parse(response.content());
                    PlanningJson.object(envelope,Set.of("plan","monitor","needs_clarification","reply"),"monitor 规划");
                    if(envelope.has("needs_clarification") && !envelope.path("needs_clarification").isBoolean()) throw new IllegalArgumentException("needs_clarification 必须是布尔值");
                    if(envelope.path("needs_clarification").asBoolean()) {
                        return terminal("NEEDS_CLARIFICATION",PlanningJson.text(envelope,"reply",200),usages,traceId,sink);
                    }
                    if(!envelope.hasNonNull("plan")) return terminal("UNSUPPORTED",PlanningJson.text(envelope,"reply",200),usages,traceId,sink);
                    var accepted=validator.validate(envelope);
                    sink.record(new TraceEvent(traceId,"MONITOR_PLAN","monitor_planner",null,accepted.candidate().toString(),null,null,null,null,null,null));
                    return new Outcome("PLANNED","监控候选计划已生成并通过校验；本次仅规划，尚未创建或启用后台任务。",accepted.candidate(),List.copyOf(usages));
                } catch(IllegalArgumentException invalid) {
                    sink.record(new TraceEvent(traceId,"MONITOR_PLAN","monitor_planner",null,response.content(),null,null,null,null,null,invalid.getMessage()));
                    messages.add(ChatMessage.assistant(response.content()==null?"":response.content(),null));
                    messages.add(ChatMessage.user("规划未通过服务端校验："+invalid.getMessage()+"。修复完整 JSON；无法支持时 plan=null 并在 reply 说明。"));
                }
            }
            return terminal("FAILED","暂时无法可靠生成监控计划，本次没有创建或启用监控任务。",usages,traceId,sink);
        } catch(RuntimeException error) { throw new Failure(error,usages); }
    }
    private Outcome terminal(String status,String reply,List<LlmUsage> usages,String traceId,TraceSink sink) {
        var normalized=new ObjectMapper().createObjectNode().put("status",status).put("reply",reply).putNull("plan").putNull("monitor");
        sink.record(new TraceEvent(traceId,"MONITOR_PLAN","monitor_planner",null,normalized.toString(),null,null,null,null,null,
                "FAILED".equals(status)?"监控规划修复次数耗尽":null));
        return new Outcome(status,reply,null,List.copyOf(usages));
    }
}
