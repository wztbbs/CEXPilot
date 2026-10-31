package com.cexpilot.api;

import com.cexpilot.conversation.ConversationService;
import com.cexpilot.conversation.QueryRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversation;

    public ConversationController(ConversationService conversation) {
        this.conversation = conversation;
    }

    @ModelAttribute
    public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> unavailable(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", "会话不存在"));
    }

    @GetMapping("/conversations")
    public Object list(@CookieValue(value=AskController.VISITOR_COOKIE,required=false) String visitor,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="30") int limit,
            @RequestParam(defaultValue="0") int offset) {
        return conversation.list(visitor,search,limit,offset);
    }

    @PostMapping(value="/conversations", consumes="application/json")
    public ResponseEntity<?> create(
            @CookieValue(value=AskController.VISITOR_COOKIE,required=false) String visitor) {
        boolean fresh=visitor==null || visitor.isBlank();
        String owner=fresh?java.util.UUID.randomUUID().toString():visitor;
        String id=conversation.createEmpty(owner);
        return ResponseEntity.ok().headers(h->{
            if(fresh) h.add(org.springframework.http.HttpHeaders.SET_COOKIE, AskController.visitorCookie(owner));
        }).body(new ConversationHistoryResponse(id,"",List.of()));
    }

    @GetMapping("/conversation/{id}")
    public ConversationHistoryResponse detail(@PathVariable String id,
            @CookieValue(value=AskController.VISITOR_COOKIE,required=false) String visitor) {
        conversation.requireOwned(id,visitor);
        return response(id);
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
        return response(conversationId);
    }

    private ConversationHistoryResponse response(String conversationId) {
        List<QueryRecord> queries = conversation.history(conversationId);
        // 父轮次与回显历史来自同一次读取，避免另一标签页恰好提交导致 token 超前于页面内容。
        String parentTurnId = queries.stream()
                .filter(q -> q.turnId() != null && java.util.Set.of("SUCCEEDED", "NEEDS_CLARIFICATION", "FAILED")
                        .contains(q.turnStatus() == null ? "" : q.turnStatus()))
                .max(java.util.Comparator.comparingInt(QueryRecord::queryNo))
                .map(QueryRecord::turnId).orElse("");
        return new ConversationHistoryResponse(conversationId, parentTurnId,
                queries.stream()
                        .map(q -> new QueryMessage(q.question(), q.answer(), q.traceId(), q.createdAt(), q.turnId(), q.turnStatus(), q.agentType(), q.planning()))
                        .toList());
    }

    public record ConversationHistoryResponse(String conversationId, String parentTurnId, List<QueryMessage> messages) {
    }

    public record QueryMessage(String question, String answer, String traceId, LocalDateTime createdAt,
                               String turnId, String turnStatus, String type, com.fasterxml.jackson.databind.JsonNode planning) {
        @com.fasterxml.jackson.annotation.JsonProperty("clarify")
        public java.util.List<com.cexpilot.dag.ClarificationField> clarify() {
            return com.cexpilot.dag.ClarificationField.fromPayload(planning);
        }
    }
}
