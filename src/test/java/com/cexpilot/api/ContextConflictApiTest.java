package com.cexpilot.api;

import com.cexpilot.conversation.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContextConflictApiTest {
    @Test void 普通接口冲突返回409及机器可识别错误码() throws Exception {
        var service = mock(AskService.class);
        when(service.ask(any(), any(), any(), any(), any(), any()))
                .thenThrow(new StaleContextException("c1", "eth-turn"));
        var mvc = MockMvcBuilders.standaloneSetup(new AskController(service, new SyncTaskExecutor())).build();
        mvc.perform(post("/api/ask").contentType("application/json")
                .content("{\"conversationId\":\"c1\",\"question\":\"它现在呢\",\"parentTurnId\":\"btc-turn\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTEXT_CONFLICT"))
                .andExpect(jsonPath("$.currentParentTurnId").value("eth-turn"));
    }

    @Test void 流式接口冲突使用error事件且不发meta或done() throws Exception {
        var service = mock(AskService.class);
        when(service.ask(any(), any(), any(), any(), any(), any()))
                .thenThrow(new StaleContextException("c1", "eth-turn"));
        var mvc = MockMvcBuilders.standaloneSetup(new AskController(service, new SyncTaskExecutor())).build();
        var result = mvc.perform(post("/api/ask/stream").contentType("application/json")
                .content("{\"conversationId\":\"c1\",\"question\":\"它现在呢\",\"parentTurnId\":\"btc-turn\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(response.contains("event:error"));
        assertTrue(response.contains("CONTEXT_CONFLICT"));
        assertFalse(response.contains("event:meta"));
        assertFalse(response.contains("event:done"));
    }

    @Test void 流式失败只有提交确认后才携带父轮次() throws Exception {
        var service = mock(AskService.class);
        when(service.ask(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            AskService.AskStreamListener listener = inv.getArgument(5);
            listener.onMeta("c1", "trace", "failed-turn");
            listener.onTurnFinished("failed-turn");
            throw new IllegalStateException("LLM 超时");
        });
        var mvc = MockMvcBuilders.standaloneSetup(new AskController(service, new SyncTaskExecutor())).build();
        var result = mvc.perform(post("/api/ask/stream").contentType("application/json")
                .content("{\"question\":\"查询\"}")).andReturn();
        var response = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
        String error = response.substring(response.indexOf("event:error"));
        assertTrue(error.contains("\"turnId\":\"failed-turn\""));
    }

    @Test void 历史父轮次来自本次返回的完成历史而不是活动任务或执行中轮次() {
        var service = mock(ConversationService.class);
        when(service.latestConversationId("v")).thenReturn("c1");
        var now = LocalDateTime.of(2026,10,13,12,0);
        when(service.history("c1")).thenReturn(List.of(
                new QueryRecord(1,"BTC","答案",null,now,"btc","SUCCEEDED"),
                new QueryRecord(2,"ETH",null,null,now,"eth","FAILED"),
                new QueryRecord(3,"重试",null,null,now,"running","RUNNING")));
        var response = new ConversationController(service).latest("v");
        assertEquals("eth", response.parentTurnId());
        assertEquals("running", response.messages().get(2).turnId());
    }
}
