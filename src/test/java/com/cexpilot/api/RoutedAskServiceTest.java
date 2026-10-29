package com.cexpilot.api;

import com.cexpilot.agent.AgentDispatcher;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.conversation.*;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoutedAskServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final ConversationService conversation=mock(ConversationService.class);
    final ConversationContextService context=mock(ConversationContextService.class);
    final DagRuntime oldRuntime=mock(DagRuntime.class);
    final AgentDispatcher dispatcher=mock(AgentDispatcher.class);
    final TraceRepository traces=mock(TraceRepository.class);
    final DbTraceSink sink=mock(DbTraceSink.class);
    final ConversationService.TurnHandle turn=new ConversationService.TurnHandle(1,"turn",7);
    AskService service;
    @BeforeEach void setup() {
        service=new AskService(conversation,context,oldRuntime,traces,sink,new LlmConfig(),Clock.fixed(Instant.parse("2026-10-27T06:00:00Z"),ZoneOffset.UTC),
                mock(ObjectProvider.class),dispatcher);
        when(conversation.getOrCreateConversation(any(),any(),any())).thenReturn("c");
        when(conversation.beginTurn(any(),any(),any(),any())).thenReturn(turn);
        when(conversation.renderContext(any())).thenReturn("");
        when(conversation.routingState(any())).thenReturn(json.createObjectNode());
        when(context.buildPlannerInput(any())).thenReturn(new PlannerInput("",null));
        when(conversation.finishRoutedTurn(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new ConversationService.TaskCommit(false,null,0,null));
    }
    @Test void monitorPlanningResponseExposesCandidateWithoutCallingOldRuntimeOrQueryTaskCommit() {
        var planning=json.createObjectNode().put("status","PLANNED").put("execution_status","NOT_CREATED");
        planning.putObject("candidate").putObject("monitor").put("action","create");
        var outcome=new ExecutionResult("候选计划已生成",json.createArrayNode(),0,0,10,5,"SUCCEEDED",null,0,1,0,0,0,0,List.of())
                .withLlmUsages(List.of(new LlmUsage("normal","fake",10,5,0.0))).withAgent("monitor",planning)
                .withRouterUsages(List.of(new LlmUsage("normal","fake",3,2,0.0)));
        when(dispatcher.execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            Consumer<String> selected=inv.getArgument(11); selected.accept("monitor"); return outcome;
        });
        var response=service.ask(null,"监控BTC", "visitor");
        assertEquals("monitor",response.type()); assertEquals(1,response.routerLlmCalls());
        assertEquals("PLANNED",response.planning().path("status").asText());
        assertEquals(0,response.toolCalls()); verifyNoInteractions(oldRuntime);
        verify(conversation).finishRoutedTurn(eq("c"),eq(turn),eq("监控BTC"),eq("SUCCEEDED"),any(),any(),isNull(),any(),isNull(),any(),any(),isNull(),eq("monitor"),eq(planning));
        verify(traces).saveRouting(anyString(),eq("monitor"),eq(1));
        verify(conversation,never()).finishTaskTurn(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void monitorTransportFailureSavesMonitorFailureAndDoesNotPoisonQueryTask() {
        when(dispatcher.execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            Consumer<String> selected=inv.getArgument(11); selected.accept("monitor");
            throw new DagRuntime.ExecutionFailure(new IllegalStateException("monitor timeout"),new DagRuntime.TurnCounters(1,0,0,0,0,0,List.of())
                    .withLlmUsages(List.of(new LlmUsage("normal","fake",null,null,null)))
                    .withRouterUsages(List.of(new LlmUsage("normal","fake",3,2,0.0))));
        });
        assertThrows(DagRuntime.ExecutionFailure.class,()->service.ask(null,"监控BTC","visitor"));
        verify(traces).saveRouting(anyString(),eq("monitor"),eq(1));
        verify(conversation).finishRoutedTurn(eq("c"),eq(turn),eq("监控BTC"),eq("FAILED"),isNull(),any(),isNull(),any(),any(),any(),any(),eq("monitor timeout"),eq("monitor"),isNull());
        verifyNoInteractions(oldRuntime);
    }
    @Test void queryClarificationIsPersistedAndExposedInOrdinaryResponseAndHistory() throws Exception {
        var payload=json.createObjectNode().put("status","NEEDS_CLARIFICATION");
        payload.putArray("clarify").addObject().put("key","base").put("label","币种").put("type","text");
        var result=new ExecutionResult("请补充币种",json.createArrayNode(),0,0,10,5,"NEEDS_CLARIFICATION","请补充币种",0,1,0,0,0,0,List.of())
                .withAgent("query",payload);
        when(dispatcher.execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            Consumer<String> selected=inv.getArgument(11); selected.accept("query"); return result;
        });
        var response=service.ask(null,"比较两所Funding","visitor");
        assertEquals("base",response.clarify().get(0).key());
        assertEquals("币种",json.readTree(json.writeValueAsString(response)).at("/clarify/0/label").asText());
        verify(conversation).finishRoutedTurn(eq("c"),eq(turn),eq("比较两所Funding"),eq("NEEDS_CLARIFICATION"),
                any(),any(),isNull(),any(),any(),any(),any(),eq("请补充币种"),eq("query"),eq(payload));
        var message=new ConversationController.QueryMessage("比较两所Funding","请补充币种","trace",null,"turn","NEEDS_CLARIFICATION","query",payload);
        assertEquals("base",json.readTree(json.writeValueAsString(message)).at("/clarify/0/key").asText());
    }

    @Test void querySseDoneIncludesClarificationFields() throws Exception {
        var ask=mock(AskService.class);
        var payload=json.createObjectNode().put("status","NEEDS_CLARIFICATION");
        payload.putArray("clarify").addObject().put("key","base").put("label","币种").put("type","text").put("reason","confirm").put("message","您填写了两个币种，请确认一个").put("value","BTC和ETH");
        var response=new AskResponse("c","trace","请补充币种",null,0,0,1,"turn",false,1,0,0,0,0,0,"query",payload,1);
        when(ask.ask(any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            assertEquals(java.util.Map.of("base","BTC和ETH"), inv.getArgument(6));
            AskService.AskStreamListener listener=inv.getArgument(5);
            listener.onMeta("c","trace","turn"); listener.onDelta("请补充币种"); listener.onDone(response); return response;
        });
        var mvc=MockMvcBuilders.standaloneSetup(new AskController(ask,new SyncTaskExecutor())).build();
        var pending=mvc.perform(post("/api/ask/stream").contentType("application/json").content("{\"question\":\"比较Funding\",\"parentTurnId\":\"parent\",\"clarificationAnswers\":{\"base\":\"BTC和ETH\"}}"))
                .andExpect(request().asyncStarted()).andReturn();
        var body=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"clarify\":[{\"key\":\"base\""));
        assertTrue(body.contains("\"reason\":\"confirm\""));
        assertTrue(body.contains("您填写了两个币种，请确认一个"));
        assertTrue(body.contains("BTC和ETH"));
    }

    @Test void sseDoneCarriesRouteAndPlanningPayload() throws Exception {
        var ask=mock(AskService.class);
        var planning=json.createObjectNode().put("status","PLANNED").put("execution_status","NOT_CREATED");
        var response=new AskResponse("c","trace","仅规划",null,0,0,1,"turn",false,1,0,0,0,0,0,"monitor",planning,1);
        when(ask.ask(any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            AskService.AskStreamListener listener=inv.getArgument(5);
            listener.onMeta("c","trace","turn"); listener.onDelta("仅规划"); listener.onTurnFinished("turn"); listener.onDone(response);
            return response;
        });
        var mvc=MockMvcBuilders.standaloneSetup(new AskController(ask,new SyncTaskExecutor())).build();
        var pending=mvc.perform(post("/api/ask/stream").contentType("application/json").content("{\"question\":\"监控BTC\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        var body=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"type\":\"monitor\"")); assertTrue(body.contains("\"planning\""));
        assertTrue(body.contains("NOT_CREATED")); assertTrue(body.contains("\"routerLlmCalls\":1"));
    }
}
