package com.cexpilot.api;

import com.cexpilot.agent.AgentDispatcher;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.conversation.*;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 固定问答必须绕过全部 LLM，并仍经过正常的轮次、持久化与 SSE 协议。 */
class PresetReplyTest {
    final ObjectMapper json=new ObjectMapper();
    final ConversationService conversation=mock(ConversationService.class);
    final ConversationContextService context=mock(ConversationContextService.class);
    final DagRuntime runtime=mock(DagRuntime.class);
    final AgentDispatcher dispatcher=mock(AgentDispatcher.class);
    final TraceRepository traces=mock(TraceRepository.class);
    final DbTraceSink sink=mock(DbTraceSink.class);
    final ConversationService.TurnHandle turn=new ConversationService.TurnHandle(1,"turn",7);
    final Instant now=Instant.parse("2026-10-31T06:00:00Z");
    HomeSuggestions suggestions;
    AskService service;
    @BeforeEach void setup() throws Exception {
        suggestions=new HomeSuggestions(new ClassPathResource("home-suggestions.yml"));
        service=new AskService(conversation,context,runtime,traces,sink,new LlmConfig(),
                Clock.fixed(now,ZoneOffset.UTC),mock(ObjectProvider.class),dispatcher,suggestions);
        when(conversation.getOrCreateConversation(any(),any(),any())).thenReturn("c");
        when(conversation.beginTurn(any(),any(),any(),any())).thenReturn(turn);
        when(conversation.finishQueryTurn(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new ConversationService.TaskCommit(false,null,0,null));
    }
    @Test void fixedAnswerSkipsRouterPlannerAndAnswerAndRecordsZeroUsage() {
        String question="你能实现哪些功能";
        var response=service.ask(null,question,"visitor");
        assertEquals(suggestions.fixedReply(question).orElseThrow().answer(),response.answer());
        assertEquals("query",response.type());
        assertEquals("preset",response.planning().path("source").asText());
        assertEquals(0,response.routerLlmCalls());
        assertEquals(0,response.plannerLlmCalls());
        assertEquals(0,response.answerLlmCalls());
        assertEquals(0,response.toolCalls());
        verifyNoInteractions(runtime,dispatcher,context);
        verify(traces).startTrace(eq(response.traceId()),eq("c"),eq(question),isNull(),eq(suggestions.version()),eq("visitor"),isNull(),eq(true));
        verify(traces).finishTrace(eq(response.traceId()),eq("SUCCESS"),eq(response.answer()),eq(0),eq(0),eq(0),eq(0),eq(0.0),anyLong(),isNull(),eq(0),eq(0),eq(0),eq(0),eq(0),eq(0));
        verify(sink).record(argThat(event->"STATIC_REPLY".equals(event.eventType()) && event.outputJson().contains("capabilities")));
        verify(sink,never()).record(argThat(event->"LLM_CALL".equals(event.eventType())));
        verify(conversation).finishQueryTurn(eq("c"),eq(turn),eq(question),eq("SUCCEEDED"),eq(response.answer()),eq(response.traceId()),isNull(),any(),isNull(),eq(now),any(),isNull(),eq(response.planning()));
        verify(conversation,never()).finishRoutedTurn(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void streamingPersistsBeforeSendingAndDisconnectedClientDoesNotRewriteSuccess() {
        var listener=mock(AskService.AskStreamListener.class);
        doThrow(new IllegalStateException("disconnected")).when(listener).onDelta(anyString());
        var response=service.ask(null,"你能实现哪些功能？","visitor",listener);
        var order=inOrder(conversation,listener);
        order.verify(conversation).finishQueryTurn(any(),any(),any(),eq("SUCCEEDED"),any(),any(),isNull(),any(),isNull(),any(),any(),isNull(),any());
        order.verify(listener).onDelta(response.answer());
        verify(conversation,never()).finishQueryTurn(any(),any(),any(),eq("FAILED"),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verifyNoInteractions(runtime,dispatcher,context);
    }
    @Test void metaFailureReleasesTurnWithoutModifyingExistingTask() {
        var listener=mock(AskService.AskStreamListener.class);
        doThrow(new IllegalStateException("disconnected")).when(listener).onMeta(any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.ask(null,"你能实现哪些功能？","visitor",listener));
        verify(conversation).finishQueryTurn(eq("c"),eq(turn),any(),eq("FAILED"),isNull(),any(),isNull(),isNull(),isNull(),eq(now),any(),eq("disconnected"),isNull());
        verifyNoInteractions(runtime,dispatcher,context);
    }
    @Test void busyConversationStillRejectsPresetRequestWithoutCreatingTrace() {
        when(conversation.beginTurn(any(),any(),any(),any())).thenReturn(null);
        assertTrue(service.ask("c","你能实现哪些功能？","visitor").busy());
        verifyNoInteractions(traces,sink,runtime,dispatcher,context);
    }
    @Test void editedQuestionUsesNormalDispatcher() {
        String edited="你能实现哪些功能？帮我查 BTC 价格";
        when(context.buildPlannerInput("c")).thenReturn(new PlannerInput("",null));
        when(conversation.finishRoutedTurn(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new ConversationService.TaskCommit(false,null,0,null));
        when(dispatcher.execute(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new ExecutionResult("正常模型回答",json.createArrayNode(),0,0,10,5,"SUCCEEDED",null).withLlmUsages(List.of()));
        assertEquals("正常模型回答",service.ask("c",edited,"visitor").answer());
        verify(dispatcher).execute(eq(edited),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(sink,never()).record(argThat(event->"STATIC_REPLY".equals(event.eventType())));
    }
}
