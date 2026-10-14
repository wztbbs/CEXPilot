package com.cexpilot.conversation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 生命周期测试存储桩：保存真实序列化快照，不模拟 MySQL 的事务/锁语义。 */
public class InMemoryTaskRepository extends ConversationRepository {
    public final Map<String, TaskState> tasks = new LinkedHashMap<>();
    public final Map<String, TaskState> snapshots = new LinkedHashMap<>();
    public final Map<String, TaskScope> scopes = new LinkedHashMap<>();
    private final Map<String, QueryRecord> questions = new LinkedHashMap<>();
    private String active;
    private String running;
    private long queryNo;
    private long token;
    public boolean acceptCommit = true;

    public InMemoryTaskRepository() { super(null); }
    @Override public ConversationStateRow acquireTurn(String conversationId, String turnId, long lease) {
        if (running == null) { running = turnId; ++queryNo; ++token; }
        return new ConversationStateRow(queryNo, running, token);
    }
    @Override public void insertRunningTurn(String conversationId, long no, String turnId, String parent,
            String question, Instant time) {
        questions.put(turnId, new QueryRecord((int)no, question, null, null, LocalDateTime.ofInstant(time, ZoneOffset.UTC), turnId, "RUNNING"));
    }
    @Override public String latestCompletedTurnId(String c) {
        return questions.values().stream().filter(q -> q.turnId() != null && !"RUNNING".equals(q.turnStatus()))
                .max(Comparator.comparingInt(QueryRecord::queryNo)).map(QueryRecord::turnId).orElse(null);
    }
    @Override public boolean ownsTurnForUpdate(String conversationId, String turnId, long fencing) {
        return acceptCommit && turnId.equals(running) && fencing == token;
    }
    @Override public boolean releaseTurn(String c, String t, long f) {
        if (t.equals(running) && f == token) { running = null; return true; }
        return false;
    }
    @Override public void touchConversation(String c, String title) {}
    @Override public TaskState findActiveTask(String c) { return active == null ? null : tasks.get(active); }
    @Override public TaskState findTask(String c, String id) { return tasks.get(id); }
    @Override public void saveTask(TaskState state) { tasks.put(state.frameId(), TaskState.fromJson(state.toJson())); }
    @Override public void activateTask(String c, TaskState state) { active = state.frameId(); }
    @Override public void finishTaskQuery(String t, String status, String answer, String trace, String logical,
            TaskState state, TaskScope scope) {
        snapshots.put(t, TaskState.fromJson(state.toJson()));
        scopes.put(t, scope);
        var q = questions.get(t);
        if (q != null) questions.put(t, new QueryRecord(q.queryNo(), q.question(), answer, trace, q.createdAt(), t, status));
    }
    @Override public void finishTurn(String t, String status, String answer, String trace, String logical, String frame) {
        var q = questions.get(t);
        if (q != null) questions.put(t, new QueryRecord(q.queryNo(), q.question(), answer, trace, q.createdAt(), t, status));
    }
    @Override public List<TaskState> recentTasks(String c, int limit) {
        return tasks.values().stream().sorted(Comparator.comparingLong(TaskState::lastQueryNo).reversed()).limit(limit).toList();
    }
    @Override public TaskState firstTask(String c) {
        return tasks.values().stream().min(Comparator.comparingLong(TaskState::firstQueryNo)).orElse(null);
    }
    @Override public List<TaskState> recentTaskVersions(String c, String id, int limit) {
        return snapshots.values().stream().filter(s -> s.frameId().equals(id))
                .sorted(Comparator.comparingLong(TaskState::version).reversed()).limit(limit).toList();
    }
    @Override public TaskState firstTaskVersion(String c, String id) {
        return snapshots.values().stream().filter(s -> s.frameId().equals(id))
                .min(Comparator.comparingLong(TaskState::version)).orElse(null);
    }
    @Override public TaskState taskVersion(String c, String id, long version) {
        return snapshots.values().stream().filter(s -> s.frameId().equals(id) && s.version() == version).findFirst().orElse(null);
    }
    @Override public TaskState firstExecutableTaskVersion(String c, String id) {
        return snapshots.values().stream().filter(s -> s.frameId().equals(id) && s.frame() != null)
                .min(Comparator.comparingLong(TaskState::version)).orElse(null);
    }
    @Override public String findActiveFrameJson(String c) {
        TaskState s = findActiveTask(c); return s == null || s.frame() == null ? null : s.frame().toJson();
    }
    @Override public List<QueryRecord> recentQueries(String c, int limit) {
        return questions.entrySet().stream().filter(e -> snapshots.containsKey(e.getKey())
                && "SUCCEEDED".equals(snapshots.get(e.getKey()).status()))
                .map(Map.Entry::getValue).sorted(Comparator.comparingInt(QueryRecord::queryNo).reversed())
                .limit(limit).sorted(Comparator.comparingInt(QueryRecord::queryNo)).toList();
    }
    @Override public List<TaskSnapshot> recentFrames(String c, int limit) { return List.of(); }
    @Override public String findPendingTaskJson(String c) { return null; }
    @Override public String findLastAttemptJson(String c) { return null; }
}
