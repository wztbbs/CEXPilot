package com.cexpilot.conversation;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Conversation Context：不做长期记忆，
 * 只把本对话最近几个 Query 注入 prompt，追问的指代消解交给 LLM 在这份上下文上完成。
 */
@Service
public class ConversationService {

    private static final int HISTORY_QUERY_LIMIT = 5;
    private static final int HISTORY_ANSWER_MAX_CHARS = 300;

    private final ConversationRepository repository;

    public ConversationService(ConversationRepository repository) {
        this.repository = repository;
    }

    /**
     * conversationId 为空时：当前约定 1 访客 1 会话，visitorId 已有会话则复用最近活跃的那个；
     * 否则新建。conversationId 不为空且不存在则按给定 id 创建。
     */
    public String getOrCreateConversation(String conversationId, String firstQuestion, String visitorId) {
        if (conversationId == null || conversationId.isBlank()) {
            String existing = visitorId == null ? null : repository.findLatestByVisitorId(visitorId);
            conversationId = existing != null ? existing : UUID.randomUUID().toString();
        }
        if (!repository.conversationExists(conversationId)) {
            repository.createConversation(conversationId, abbreviate(firstQuestion, 60), visitorId);
        }
        return conversationId;
    }

    /** 会话的全部问答历史（页面回显用），无会话时返回空表。 */
    public List<QueryRecord> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return repository.allQueries(conversationId);
    }

    /** 访客最近活跃的会话 id；没有返回 null。 */
    public String latestConversationId(String visitorId) {
        return repository.findLatestByVisitorId(visitorId);
    }

    /** 渲染注入 system prompt 的最近对话上下文。 */
    public String renderContext(String conversationId) {
        List<QueryRecord> queries = repository.recentQueries(conversationId, HISTORY_QUERY_LIMIT);
        if (queries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (QueryRecord query : queries) {
            sb.append("用户：").append(query.question()).append('\n');
            if (query.answer() != null) {
                sb.append("助手：").append(abbreviate(query.answer(), HISTORY_ANSWER_MAX_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    public void recordQuery(String conversationId, String question, String answer, String traceId) {
        int queryNo = repository.nextQueryNo(conversationId);
        repository.recordQuery(conversationId, queryNo, question, answer, traceId);
        repository.touchConversation(conversationId, null);
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
