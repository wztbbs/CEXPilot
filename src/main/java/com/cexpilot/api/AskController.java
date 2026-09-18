package com.cexpilot.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AskController {

    /** 访客标识 cookie：无注册体系下关联同一浏览器的多次问答，用于统计与 case 追踪。 */
    static final String VISITOR_COOKIE = "cexpilot_uid";

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest request,
                                 @CookieValue(value = VISITOR_COOKIE, required = false) String visitorId) {
        boolean newVisitor = visitorId == null || visitorId.isBlank();
        String resolvedVisitorId = newVisitor ? UUID.randomUUID().toString() : visitorId;
        try {
            AskResponse response = askService.ask(request.conversationId(), request.question(),
                    resolvedVisitorId);
            return ResponseEntity.ok()
                    .headers(headers -> {
                        if (newVisitor) {
                            headers.add(HttpHeaders.SET_COOKIE, visitorCookie(resolvedVisitorId));
                        }
                    })
                    .body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "问答处理失败: " + e.getMessage()));
        }
    }

    private static String visitorCookie(String visitorId) {
        return ResponseCookie.from(VISITOR_COOKIE, visitorId)
                .path("/")
                .maxAge(Duration.ofDays(365))
                .sameSite("Lax")
                .build()
                .toString();
    }
}
