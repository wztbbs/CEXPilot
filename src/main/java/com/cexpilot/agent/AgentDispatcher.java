package com.cexpilot.agent;

import com.cexpilot.conversation.PlannerInput;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.monitor.MonitorPlanner;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Consumer;

/** 公共路由；Query 继续原 DagRuntime，Monitor 只生成待确认草稿，不执行行情检查。 */
@Component
public class AgentDispatcher {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final IntentRouter router;
    private final DagRuntime query;
    private final MonitorPlanner monitor;
    private final PromptStore prompts;
    private final com.cexpilot.monitor.MonitorConversationFlow flow;
    public AgentDispatcher(IntentRouter router,DagRuntime query,MonitorPlanner monitor,PromptStore prompts) {
        this(router,query,monitor,prompts,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentDispatcher(IntentRouter router,DagRuntime query,MonitorPlanner monitor,PromptStore prompts,
                           com.cexpilot.monitor.MonitorConversationFlow flow) {
        this.router=router; this.query=query; this.monitor=monitor; this.prompts=prompts;
        this.flow=flow;
    }
    public String promptVersion() {
        return PromptStore.fingerprint(query.promptVersion()+prompts.version(IntentRouter.PROMPT)+prompts.version(MonitorPlanner.PROMPT));
    }
    public ExecutionResult execute(String question,String history,String traceId,TraceSink sink,Consumer<String> delta,
            RequestContext request,Consumer<String> progress,Consumer<CompiledPlan> compiledPlanSink,PlannerInput input,
            EvidenceSession session,JsonNode routingState,Consumer<String> selectedAgent) {
        return execute(question,history,traceId,sink,delta,request,progress,compiledPlanSink,input,session,routingState,selectedAgent,null);
    }
    public ExecutionResult execute(String question,String history,String traceId,TraceSink sink,Consumer<String> delta,
            RequestContext request,Consumer<String> progress,Consumer<CompiledPlan> compiledPlanSink,PlannerInput input,
            EvidenceSession session,JsonNode routingState,Consumer<String> selectedAgent,String visitor) {
        if(flow!=null && visitor!=null) routingState=flow.enrichState(visitor,routingState);
        var context=context(history,input,routingState,request);
        IntentRouter.Decision decision;
        if(progress!=null) progress.accept("正在识别查询或监控意图…");
        try { decision=router.route(question,routerContext(context),traceId,sink); }
        catch(IntentRouter.Failure failure) {
            throw new DagRuntime.ExecutionFailure(failure.getCause(),new DagRuntime.TurnCounters(0,0,0,0,0,0,List.of())
                    .withRouterUsages(failure.usages));
        }
        selectedAgent.accept(decision.route());
        if("query".equals(decision.route())) {
            try {
                return query.execute(question,history,traceId,sink,delta,request,progress,compiledPlanSink,queryInput(input,routingState),session)
                        .withRouterUsages(decision.usages());
            } catch(DagRuntime.ExecutionFailure failure) {
                throw new DagRuntime.ExecutionFailure(failure.getCause(),failure.counters.withRouterUsages(decision.usages()),failure.taskScope,failure.evidence);
            } catch(RuntimeException failure) {
                throw new DagRuntime.ExecutionFailure(failure,new DagRuntime.TurnCounters(0,0,0,0,0,0,List.of()).withRouterUsages(decision.usages()));
            }
        }
        if("clarify".equals(decision.route())) {
            var result=planningResult("clarify","NEEDS_CLARIFICATION",decision.question(),null,List.of()).withRouterUsages(decision.usages());
            emit(delta,result,traceId); return result;
        }
        if(progress!=null) progress.accept("正在解析监控条件，生成后需要你确认才会启用…");
        MonitorPlanner.Outcome outcome;
        try { outcome=monitor.plan(question,context,traceId,sink); }
        catch(MonitorPlanner.Failure failure) {
            throw new DagRuntime.ExecutionFailure(failure.getCause(),new DagRuntime.TurnCounters(failure.usages.size(),0,0,0,0,0,List.of())
                    .withLlmUsages(failure.usages).withRouterUsages(decision.usages()));
        }
        var result=planningResult("monitor",outcome.status(),outcome.reply(),outcome.candidate(),outcome.usages());
        if(flow!=null && visitor!=null && outcome.candidate()!=null) {
            try {
                var card=flow.saveDraft(visitor,session,question,outcome.candidate(),request.userZone().getId());
                var payload=((ObjectNode)result.agentPayload()).deepCopy().put("status","PENDING_CONFIRMATION").put("execution_status","NOT_ENABLED");
                payload.set("confirmation",card); payload.set("candidate",card.path("candidate"));
                var text=new StringBuilder("我将监控条件整理为以下规则（全部同时满足）：\n");
                for(var rule:card.path("rules")) text.append("• ").append(rule.path("description").asText()).append('\n');
                text.append("每5分钟检查一次，连续 ").append(card.path("requiredHits").asInt()).append(" 次满足后发邮件；条件恢复后才会再次提醒。\n");
                if(!card.at("/profile/emailBound").asBoolean()) text.append("请填写通知邮箱，并核对后点击确认。当前草稿尚未启用。");
                else text.append("请核对下面的规则和收件邮箱，点击确认后才会启用。");
                if("DRY_RUN".equals(card.path("mailMode").asText())) text.append("\n当前为邮件模拟模式，只记录日志，不会发送真实邮件。");
                result=new ExecutionResult(text.toString(),result.evidence(),0,0,result.promptTokens(),result.completionTokens(),"SUCCEEDED",null,0,
                        result.plannerLlmCalls(),0,0,0,0,List.of()).withLlmUsages(outcome.usages()).withAgent("monitor",payload);
                sink.record(new TraceEvent(traceId,"MONITOR_DRAFT","monitor_flow",null,card.toString(),null,null,null,null,null,null));
            } catch(RuntimeException failure) {
                throw new DagRuntime.ExecutionFailure(failure,new DagRuntime.TurnCounters(outcome.usages().size(),0,0,0,0,0,List.of())
                        .withLlmUsages(outcome.usages()).withRouterUsages(decision.usages()));
            }
        }
        result=result.withRouterUsages(decision.usages());
        emit(delta,result,traceId); return result;
    }
    private void emit(Consumer<String> delta,ExecutionResult result,String traceId) {
        if(delta==null) return;
        try { delta.accept(result.answer()); }
        catch(RuntimeException failure) {
            throw new DagRuntime.ExecutionFailure(failure,new DagRuntime.TurnCounters(result.plannerLlmCalls(),0,0,0,0,0,List.of(),
                    result.llmUsages(),result.routerLlmCalls()));
        }
    }
    private ExecutionResult planningResult(String agent,String status,String reply,JsonNode candidate,List<LlmUsage> usages) {
        var payload=JSON.createObjectNode().put("status",status).put("execution_status","NOT_CREATED");
        if(candidate!=null) payload.set("candidate",candidate);
        String turn="NEEDS_CLARIFICATION".equals(status)?status:"FAILED".equals(status)?status:"SUCCEEDED";
        return new ExecutionResult(reply,JSON.createArrayNode(),0,0,LlmUsage.knownPromptTokens(usages),LlmUsage.knownCompletionTokens(usages),
                turn,"NEEDS_CLARIFICATION".equals(status)?reply:null,0,usages.size(),0,0,0,0,List.of())
                .withLlmUsages(usages).withAgent(agent,payload);
    }
    private ObjectNode context(String history,PlannerInput input,JsonNode state,RequestContext request) {
        var result=JSON.createObjectNode();
        result.put("current_agent",state==null?"query":state.path("active_agent").asText("query"));
        result.put("recent_dialogue",clip(history,2200));
        result.set("monitor_context",state==null?JSON.createObjectNode():state.path("monitor_context"));
        var task=result.putObject("query_task");
        if(input!=null && input.currentTask()!=null) {
            task.put("question",input.currentTask().originQuestion()).put("status",input.currentTask().status());
        }
        if(input!=null && input.activeFrame()!=null) {
            var targets=task.putArray("targets");
            input.activeFrame().targets().stream().limit(8).forEach(t->{
                var target=targets.addObject().put("metric",t.metric()).put("exchange",t.exchange());
                target.set("instrument",t.instrument()); target.set("time",t.timeExpression());
            });
        }
        result.put("timezone",request.userZone().getId()).put("request_time",request.requestTime().toString());
        return result;
    }
    private ObjectNode routerContext(ObjectNode full) {
        var result=full.deepCopy();
        // Router 不加载完整 Plan、目录或历史事实，保留近期步骤和候选条件的简短摘要。
        var monitor=JSON.createObjectNode();
        JsonNode prior=full.path("monitor_context");
        for(String key:List.of("last_question","last_reply","status")) if(prior.has(key)) monitor.set(key,prior.get(key));
        if(prior.has("candidate")) monitor.set("conditions",prior.at("/candidate/monitor/conditions"));
        if(prior.has("confirmation")) monitor.put("draft_status",prior.at("/confirmation/status").asText());
        result.set("monitor_context",monitor); return result;
    }
    private PlannerInput queryInput(PlannerInput input,JsonNode state) {
        if(input==null || state==null || !"monitor".equals(state.path("active_agent").asText())) return input;
        var summary=JSON.createObjectNode();
        var candidate=state.at("/monitor_context/candidate");
        summary.set("instruments",candidate.at("/plan/metrics"));
        String extra="\n【跨 Agent 查询】上一轮讨论的是监控候选计划。上文查询任务仅是历史候选，不能默认作为本轮当前任务。"
                +"用户若询问监控中的对象，可参考以下候选定义解析指代；本轮新建一次查询，明确回访查询任务时可用历史索引。"
                +"这不是行情事实，也不是执行监控的指令："+summary;
        return new PlannerInput(input.contextText()+extra,null,input.evidence(),input.textHistoryCount(),input.tasks(),null);
    }
    private static String clip(String text,int max) { return text==null?"":text.length()<=max?text:text.substring(text.length()-max); }
}
