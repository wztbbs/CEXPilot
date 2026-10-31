package com.cexpilot.agent;

import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class IntentRouter {
    public static final String PROMPT="intent_router";
    public record Decision(String route,String question,List<LlmUsage> usages) {}
    public static class Failure extends RuntimeException {
        public final List<LlmUsage> usages;
        Failure(RuntimeException cause,List<LlmUsage> usages) { super(cause); this.usages=List.copyOf(usages); }
    }
    private final PlanningLlm llm;
    private final PromptStore prompts;
    public IntentRouter(PlanningLlm llm,PromptStore prompts) { this.llm=llm; this.prompts=prompts; }
    public Decision route(String question,JsonNode context,String traceId,TraceSink sink) {
        var messages=new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(prompts.render(PROMPT,Map.of())));
        var input=new ObjectMapper().createObjectNode();
        input.set("context",orderedContext(context)); input.put("question",question);
        messages.add(ChatMessage.user(input.toString()));
        var usages=new ArrayList<LlmUsage>();
        try {
            for(int attempt=0;attempt<2;attempt++) {
                var response=llm.call("router",PROMPT,messages,traceId,sink,usages);
                try {
                    if(response.hasToolCalls()) throw new IllegalArgumentException("路由不能调用工具");
                    var parsed=PlanningJson.parse(response.content());
                    PlanningJson.object(parsed,Set.of("route","question"),"路由结果");
                    String route=PlanningJson.text(parsed,"route",16);
                    if(!Set.of("query","monitor","clarify").contains(route)) throw new IllegalArgumentException("route 仅支持 query/monitor/clarify");
                    String clarify="clarify".equals(route)?PlanningJson.text(parsed,"question",200):null;
                    sink.record(new TraceEvent(traceId,"ROUTE_DECISION","intent_router",null,parsed.toString(),null,null,null,null,null,null));
                    return new Decision(route,clarify,List.copyOf(usages));
                } catch(IllegalArgumentException invalid) {
                    sink.record(new TraceEvent(traceId,"ROUTE_DECISION","intent_router",null,response.content(),null,null,null,null,null,invalid.getMessage()));
                    messages.add(ChatMessage.assistant(response.content()==null?"":response.content(),null));
                    messages.add(ChatMessage.user("输出无效："+invalid.getMessage()+"。只返回合法的路由 JSON。"));
                }
            }
            String fallback="请确认：你想查询一次行情，还是创建或管理持续监控？";
            var fallbackJson=new ObjectMapper().createObjectNode().put("route","clarify").put("question",fallback);
            sink.record(new TraceEvent(traceId,"ROUTE_DECISION","intent_router","{\"validation_fallback\":true}",fallbackJson.toString(),null,null,null,null,null,null));
            return new Decision("clarify",fallback,List.copyOf(usages));
        } catch(RuntimeException error) { throw new Failure(error,usages); }
    }

    /** 仅调整字段顺序：稳定状态在前，历史和精确请求时间在后；所有字段和值保持不变。 */
    private JsonNode orderedContext(JsonNode context) {
        if(context==null || !context.isObject()) return context;
        var ordered=new ObjectMapper().createObjectNode();
        for(String key:List.of("current_agent","timezone","query_task","monitor_context"))
            if(context.has(key)) ordered.set(key,context.get(key));
        context.fields().forEachRemaining(field->{
            if(!ordered.has(field.getKey()) && !Set.of("recent_dialogue","request_time").contains(field.getKey()))
                ordered.set(field.getKey(),field.getValue());
        });
        for(String key:List.of("recent_dialogue","request_time"))
            if(context.has(key)) ordered.set(key,context.get(key));
        return ordered;
    }
}
