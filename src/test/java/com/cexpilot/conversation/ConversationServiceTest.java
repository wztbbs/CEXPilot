package com.cexpilot.conversation;

import com.cexpilot.config.ConversationConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    private static final Instant T0 = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    void 登记轮次成功时写入RUNNING行并返回句柄() {
        when(repository.acquireTurn(eq("c1"), anyString(), anyLong()))
                .thenAnswer(inv -> new ConversationRepository.ConversationStateRow(3, inv.getArgument(1), 7));

        ConversationService.TurnHandle turn = service.beginTurn("c1", "BTC 多少钱", "parent-1", T0);

        assertNotNull(turn);
        assertEquals(3, turn.queryNo()); // last_query_no=3 就是本轮已分配的序号
        assertEquals(7, turn.fencingToken());
        // started_at 用应用时钟（requestTime）写入，不依赖 DB 的 NOW()，避免服务器/会话时区错位
        verify(repository).insertRunningTurn(eq("c1"), eq(3L), eq(turn.turnId()), eq("parent-1"),
                eq("BTC 多少钱"), eq(T0));
    }

    @Test
    void 会话已有执行中轮次时登记返回忙碌() {
        when(repository.acquireTurn(eq("c1"), anyString(), anyLong()))
                .thenReturn(new ConversationRepository.ConversationStateRow(4, "other-turn", 7));

        assertNull(service.beginTurn("c1", "BTC 多少钱", null, T0));
        verify(repository, never()).insertRunningTurn(anyString(), anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void 提交轮次带fencingToken释放占用() {
        ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "t1", 7);

        service.finishTurn("c1", turn, "SUCCEEDED", "答案", "trace-1", null, null);

        verify(repository).finishTurn("t1", "SUCCEEDED", "答案", "trace-1", null);
        verify(repository, never()).saveActiveFrame(anyString(), anyString(), anyLong(), anyString());
        // 成功 = 澄清任务与失败尝试都已了结（R1/R2）
        verify(repository).clearTaskState("c1", "t1", 7);
        verify(repository).releaseTurn("c1", "t1", 7);
        verify(repository).touchConversation("c1", null);
    }

    @Test
    void 成功轮次提交时覆盖活动frame() {
        ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "t1", 7);

        service.finishTurn("c1", turn, "SUCCEEDED", "答案", "trace-1", "{\"metrics\":[]}", "{\"frameId\":\"f1\"}");

        verify(repository).finishTurn("t1", "SUCCEEDED", "答案", "trace-1", "{\"metrics\":[]}");
        verify(repository).saveActiveFrame("c1", "t1", 7, "{\"frameId\":\"f1\"}");
        verify(repository).clearTaskState("c1", "t1", 7);
    }

    @Test
    void 失败轮次保存lastAttempt不清空澄清任务() {
        ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "t1", 7);

        service.finishTurn("c1", turn, "FAILED", null, "trace-1", null, null,
                "{\"turnId\":\"t1\",\"question\":\"ETH 昨日收盘价\",\"status\":\"FAILED\"}");

        verify(repository).saveLastAttempt(eq("c1"), eq("t1"), eq(7L), anyString());
        verify(repository, never()).clearTaskState(anyString(), anyString(), anyLong());
    }

    @Test
    void 澄清轮次不清空任务状态也不写lastAttempt() {
        ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "t1", 7);

        service.finishTurn("c1", turn, "NEEDS_CLARIFICATION", "哪家交易所？", "trace-1", null, null, null);

        verify(repository, never()).clearTaskState(anyString(), anyString(), anyLong());
        verify(repository, never()).saveLastAttempt(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void 澄清任务独立保存且带原问题() {
        ConversationService.TurnHandle turn = new ConversationService.TurnHandle(3, "t1", 7);

        service.recordPendingTask("c1", turn, "BTC 和 ETH 谁的费率均值更高？", "您想看哪家交易所？");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).savePendingTask(eq("c1"), eq("t1"), eq(7L), captor.capture());
        assertTrue(captor.getValue().contains("BTC 和 ETH 谁的费率均值更高？"));
        assertTrue(captor.getValue().contains("您想看哪家交易所？"));
        assertTrue(captor.getValue().contains("t1"));
    }
}
