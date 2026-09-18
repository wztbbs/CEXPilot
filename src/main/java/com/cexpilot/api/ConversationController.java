package com.cexpilot.api;

import com.cexpilot.conversation.ConversationService;
import com.cexpilot.conversation.QueryRecord;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversation;

    public ConversationController(ConversationService conversation) {
        this.conversation = conversation;
    }

    /**
     * 拉取访客最近会话及其全部问答历史（页面加载时回显）。
     * 无 cookie 或无会话时返回空历史，前端按首次访问处理。
     */
    @GetMapping("/conversation/latest")
    public ConversationHistoryResponse latest(
            @CookieValue(value = AskController.VISITOR_COOKIE, required = false) String visitorId) {
        String conversationId = visitorId == null || visitorId.isBlank()
                ? null
                : conversation.latestConversationId(visitorId);
        List<QueryRecord> queries = conversation.history(conversationId);
        return new ConversationHistoryResponse(conversationId,
                queries.stream()
                        .map(q -> new QueryMessage(q.question(), q.answer(), q.traceId(), q.createdAt()))
                        .toList());
    }

    public record ConversationHistoryResponse(String conversationId, List<QueryMessage> messages) {
    }

    public record QueryMessage(String question, String answer, String traceId, LocalDateTime createdAt) {
    }
}
