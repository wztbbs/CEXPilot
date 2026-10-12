package com.cexpilot.conversation;

import com.cexpilot.config.ConversationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final ConversationService service = new ConversationService(repository, new ConversationConfig());

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

    @Test
    void 登记轮次成功时写入RUNNING行并返回句柄() {
        when(repository.acquireTurn(eq("c1"), anyString(), anyLong()))
                .thenAnswer(inv -> new ConversationRepository.ConversationStateRow(4, inv.getArgument(1), 7));

        ConversationService.TurnHandle turn = service.beginTurn("c1", "BTC 多少钱", "parent-1");

        assertNotNull(turn);
        assertEquals(3, turn.queryNo()); // next_query_no=4 表示本轮分到 3
        assertEquals(7, turn.fencingToken());
        verify(repository).insertRunningTurn(eq("c1"), eq(3L), eq(turn.turnId()), eq("parent-1"), eq("BTC 多少钱"));
    }

    @Test
    void 会话已有执行中轮次时登记返回忙碌() {
        when(repository.acquireTurn(eq("c1"), anyString(), anyLong()))
                .thenReturn(new ConversationRepository.ConversationStateRow(4, "other-turn", 7));

        assertNull(service.beginTurn("c1", "BTC 多少钱", null));
        verify(repository, never()).insertRunningTurn(anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void 提交轮次带fencingToken释放占用() {
        ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "t1", 7);

        service.finishTurn("c1", turn, "SUCCEEDED", "答案", "trace-1", null, null);

        verify(repository).finishTurn("t1", "SUCCEEDED", "答案", "trace-1", null);
        verify(repository, never()).saveActiveFrame(anyString(), anyString(), anyLong(), anyString());
        verify(repository).releaseTurn("c1", "t1", 7);
        verify(repository).touchConversation("c1", null);
    }

    @Test
    void 成功轮次提交时覆盖活动frame() {
        ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "t1", 7);

        service.finishTurn("c1", turn, "SUCCEEDED", "答案", "trace-1", "{\"metrics\":[]}", "{\"frameId\":\"f1\"}");

        verify(repository).finishTurn("t1", "SUCCEEDED", "答案", "trace-1", "{\"metrics\":[]}");
        verify(repository).saveActiveFrame("c1", "t1", 7, "{\"frameId\":\"f1\"}");
    }
}
