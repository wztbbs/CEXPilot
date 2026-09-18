package com.cexpilot.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final ConversationService service = new ConversationService(repository);

    @Test
    void 访客已有会话时复用最近会话() {
        when(repository.findLatestByVisitorId("v1")).thenReturn("conv-existing");
        when(repository.conversationExists("conv-existing")).thenReturn(true);

        String id = service.getOrCreateConversation(null, "BTC 多少钱", "v1");

        assertEquals("conv-existing", id);
        verify(repository, never()).createConversation(anyString(), any(), any());
    }

    @Test
    void 访客无会话时新建并绑定访客() {
        when(repository.findLatestByVisitorId("v1")).thenReturn(null);
        when(repository.conversationExists(anyString())).thenReturn(false);

        String id = service.getOrCreateConversation(null, "BTC 多少钱", "v1");

        assertTrue(id != null && !id.isBlank());
        verify(repository).createConversation(eq(id), any(), eq("v1"));
    }

    @Test
    void 显式传入会话id时不查访客会话() {
        when(repository.conversationExists("conv-given")).thenReturn(true);

        String id = service.getOrCreateConversation("conv-given", "BTC 多少钱", "v1");

        assertEquals("conv-given", id);
        verify(repository, never()).findLatestByVisitorId(anyString());
    }

    @Test
    void 无访客标识时直接新建会话() {
        when(repository.conversationExists(anyString())).thenReturn(false);

        String id = service.getOrCreateConversation(null, "BTC 多少钱", null);

        assertTrue(id != null && !id.isBlank());
        verify(repository, never()).findLatestByVisitorId(anyString());
        verify(repository).createConversation(eq(id), any(), eq(null));
    }

    @Test
    void 历史查询对空会话返回空表() {
        assertTrue(service.history(null).isEmpty());
        assertTrue(service.history("  ").isEmpty());
    }
}
