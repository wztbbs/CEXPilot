package com.cexpilot.agent;

import com.cexpilot.config.LlmClientConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.List;

/** 路由/监控规划的公共调用审计，不涉及工具执行；失败调用也计入 usage。 */
@Component
public class PlanningLlm {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final LlmClient client;
    private final LlmConfig config;
    private final PromptStore prompts;
    public PlanningLlm(@Qualifier(LlmClientConfig.NORMAL_LLM_CLIENT) LlmClient client,LlmConfig config,PromptStore prompts) {
        this.client=client; this.config=config; this.prompts=prompts;
    }
    public ChatResponse call(String stage,String prompt,List<ChatMessage> messages,String traceId,TraceSink sink,List<LlmUsage> usages) {
        var input=JSON.createObjectNode().put("stage",stage).put("attempt",usages.size()+1)
                .put("model",config.getNormal().getModel()).put("model_role","normal").put("prompt_version",prompts.version(prompt));
        input.set("messages",LlmTraceSerializer.messagesToArray(messages));
        input.set("request_parameters",client.requestParameters(null));
        long start=System.nanoTime();
        ChatResponse response;
        try { response=client.chat(List.copyOf(messages),null); }
        catch (RuntimeException error) {
            usages.add(LlmUsage.of("normal",config.getNormal(),null));
            sink.record(TraceEvent.llmCall(traceId,stage,input.toString(),null,(System.nanoTime()-start)/1_000_000,
                    null,null,null,null,error.getMessage()));
            throw error;
        }
        usages.add(LlmUsage.of("normal",config.getNormal(),response));
        sink.record(TraceEvent.llmCall(traceId,stage,input.toString(),LlmTraceSerializer.responseToJson(response),
                (System.nanoTime()-start)/1_000_000,response.promptTokens(),response.completionTokens(),response.ttftMs(),response.cachedTokens(),null));
        return response;
    }
}
