package com.cexpilot.conversation;

import com.cexpilot.config.ConversationConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同会话串行约束与序号原子分配的并发验证。用内存版 Repository 模拟
 * acquireTurn 的行锁语义（真实原子性由 InnoDB 行锁 + uk_query_conv_no 唯一键兜底）。
 */
class ConversationTurnConcurrencyTest {

    /** 内存模拟 conversation_state 行：acquireTurn 是临界区，语义与 SQL 版 IF(...) 一致。 */
    static class InMemoryRepository extends ConversationRepository {

        record State(long lastQueryNo, String runningTurnId, long fencingToken, long leaseUntilMillis) {
        }

        private final Map<String, State> states = new ConcurrentHashMap<>();
        final Set<Long> issuedQueryNos = ConcurrentHashMap.newKeySet();

        InMemoryRepository() {
            super(null);
        }

        @Override
        public synchronized ConversationStateRow acquireTurn(String conversationId, String turnId, long leaseMillis) {
            long now = System.currentTimeMillis();
            State current = states.get(conversationId);
            if (current == null) {
                states.put(conversationId, new State(1, turnId, 1, now + leaseMillis));
                return new ConversationStateRow(1, turnId, 1);
            }
            if (current.runningTurnId() == null || current.leaseUntilMillis() <= now) {
                State next = new State(current.lastQueryNo() + 1, turnId,
                        current.fencingToken() + 1, now + leaseMillis);
                states.put(conversationId, next);
                return new ConversationStateRow(next.lastQueryNo(), turnId, next.fencingToken());
            }
            return new ConversationStateRow(current.lastQueryNo(), current.runningTurnId(), current.fencingToken());
        }

        @Override
        public void insertRunningTurn(String conversationId, long queryNo, String turnId, String parentTurnId,
                                      String question, java.time.Instant requestTime) {
            issuedQueryNos.add(queryNo);
        }

        @Override
        public void finishTurn(String turnId, String status, String answer, String traceId, String logicalPlanJson) {
        }

        @Override
        public synchronized boolean saveActiveFrame(String conversationId, String turnId, long fencingToken, String frameJson) {
            State current = states.get(conversationId);
            return current != null && turnId.equals(current.runningTurnId()) && fencingToken == current.fencingToken();
        }

        @Override
        public synchronized boolean releaseTurn(String conversationId, String turnId, long fencingToken) {
            State current = states.get(conversationId);
            if (current != null && turnId.equals(current.runningTurnId()) && fencingToken == current.fencingToken()) {
                states.put(conversationId, new State(current.lastQueryNo(), null, current.fencingToken(), 0));
                return true;
            }
            return false;
        }

        @Override
        public void touchConversation(String conversationId, String title) {
        }

        // R1/R2 任务状态：并发语义与占用无关，内存版只做 no-op 保证 SUCCEEDED 路径不触库
        @Override
        public boolean savePendingTask(String conversationId, String turnId, long fencingToken, String pendingJson) {
            return true;
        }

        @Override
        public boolean saveLastAttempt(String conversationId, String turnId, long fencingToken, String attemptJson) {
            return true;
        }

        @Override
        public void clearTaskState(String conversationId, String turnId, long fencingToken) {
        }

        /** 测试辅助：把当前占用租约置为已过期，模拟"执行者崩溃后租约到期"。 */
        synchronized void expireLease(String conversationId) {
            State current = states.get(conversationId);
            if (current != null && current.runningTurnId() != null) {
                states.put(conversationId, new State(current.lastQueryNo(), current.runningTurnId(),
                        current.fencingToken(), 0));
            }
        }
    }

    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-12T00:00:00Z");

    private final InMemoryRepository repository = new InMemoryRepository();
    private final ConversationConfig config = new ConversationConfig();
    private final ConversationService service = new ConversationService(repository, config);

    @Test
    void 已有零计数状态从第一轮开始且失败轮次也保留序号() {
        repository.states.put("c1", new InMemoryRepository.State(0, null, 0, 0));

        ConversationService.TurnHandle first = service.beginTurn("c1", "问题", null, NOW);
        assertNotNull(first);
        assertEquals(1, first.queryNo());
        assertNull(service.beginTurn("c1", "忙碌请求", null, NOW));

        service.finishTurn("c1", first, "FAILED", "失败", "trace-1", null, null);
        ConversationService.TurnHandle second = service.beginTurn("c1", "重试", null, NOW);
        assertNotNull(second);
        assertEquals(2, second.queryNo(), "失败轮次保留序号，忙碌请求不消耗序号");
    }

    @Test
    void 已有最后序号继续递增() {
        repository.states.put("c1", new InMemoryRepository.State(37, null, 8, 0));

        ConversationService.TurnHandle turn = service.beginTurn("c1", "追问", null, NOW);

        assertNotNull(turn);
        assertEquals(38, turn.queryNo());
        assertEquals(9, turn.fencingToken());
    }

    @Test
    void 同会话并发登记只有一个成功且序号唯一() throws Exception {
        config.setTurnLease(Duration.ofMinutes(10));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ConversationService.TurnHandle>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return service.beginTurn("c1", "问题", null, NOW);
            }));
        }
        gate.countDown();
        List<ConversationService.TurnHandle> acquired = new ArrayList<>();
        for (Future<ConversationService.TurnHandle> f : futures) {
            ConversationService.TurnHandle h = f.get(10, TimeUnit.SECONDS);
            if (h != null) {
                acquired.add(h);
            }
        }
        pool.shutdown();

        assertEquals(1, acquired.size(), "同会话并发下只能有一个请求拿到执行占用");
        assertEquals(1, acquired.get(0).queryNo());

        // 释放后下一轮拿到递增序号，且全程无重复
        service.finishTurn("c1", acquired.get(0), "SUCCEEDED", "答案", "trace-1", null, null);
        ConversationService.TurnHandle next = service.beginTurn("c1", "追问", null, NOW);
        assertNotNull(next);
        assertEquals(2, next.queryNo());
        assertTrue(next.fencingToken() > acquired.get(0).fencingToken());
        assertEquals(2, repository.issuedQueryNos.size(), "发出的 query_no 不允许重复");
    }

    @Test
    void 过期租约允许新请求接管且旧token无法覆盖() {
        config.setTurnLease(Duration.ofMinutes(10));
        ConversationService.TurnHandle stale = service.beginTurn("c1", "问题1", null, NOW);
        assertNotNull(stale);

        repository.expireLease("c1"); // 旧执行者卡住/崩溃，租约到期
        ConversationService.TurnHandle takeover = service.beginTurn("c1", "问题2", null, NOW);
        assertNotNull(takeover, "租约过期后新请求应能接管执行占用");
        assertEquals(2, takeover.queryNo());

        // 旧执行者晚到：fencing token 已失效，释放失败，不清掉新占用
        org.junit.jupiter.api.Assertions.assertFalse(repository.releaseTurn("c1", stale.turnId(), stale.fencingToken()));
        assertNull(service.beginTurn("c1", "问题3", null, NOW), "新占用未被旧执行者释放时，会话仍忙碌");

        service.finishTurn("c1", takeover, "SUCCEEDED", "答案", "trace-2", null, null);
        assertNotNull(service.beginTurn("c1", "问题3", null, NOW));
    }

    @Test
    void 不同会话互不影响() {
        config.setTurnLease(Duration.ofMinutes(10));
        assertNotNull(service.beginTurn("c1", "问题", null, NOW));
        assertNotNull(service.beginTurn("c2", "问题", null, NOW), "c1 占用不影响 c2");
    }
}
