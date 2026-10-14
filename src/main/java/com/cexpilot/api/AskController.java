package com.cexpilot.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    /** 访客标识 cookie：无注册体系下关联同一浏览器的多次问答，用于统计与 case 追踪。 */
    static final String VISITOR_COOKIE = "cexpilot_uid";

    private final AskService askService;
    private final TaskExecutor taskExecutor;

    public AskController(AskService askService,
                         @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.askService = askService;
        this.taskExecutor = taskExecutor;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest request,
                                 @CookieValue(value = VISITOR_COOKIE, required = false) String visitorId) {
        boolean newVisitor = visitorId == null || visitorId.isBlank();
        String resolvedVisitorId = newVisitor ? UUID.randomUUID().toString() : visitorId;
        try {
            AskResponse response = askService.ask(request.conversationId(), request.question(),
                    resolvedVisitorId, request.timezone(), request.parentTurnId(), null);
            return ResponseEntity.ok()
                    .headers(headers -> {
                        if (newVisitor) {
                            headers.add(HttpHeaders.SET_COOKIE, visitorCookie(resolvedVisitorId));
                        }
                    })
                    .body(response);
        } catch (com.cexpilot.conversation.StaleContextException e) {
            return ResponseEntity.status(409).body(e.response());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "问答处理失败: " + e.getMessage()));
        }
    }

    /**
     * 流式问答（SSE）：meta（会话与 trace 标识）→ progress × N（阶段进度）→ delta × N（答案增量）→ done（统计）；
     * 任何阶段失败都以 error 事件收尾。问答编排在工作线程执行，trace 落库与非流式一致。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> askStream(
            @RequestBody AskRequest request,
            @CookieValue(value = VISITOR_COOKIE, required = false) String visitorId) {
        boolean newVisitor = visitorId == null || visitorId.isBlank();
        String resolvedVisitorId = newVisitor ? UUID.randomUUID().toString() : visitorId;

        SseEmitter emitter = new SseEmitter(Duration.ofSeconds(300).toMillis());
        taskExecutor.execute(() -> {
            var completedTurn = new java.util.concurrent.atomic.AtomicReference<String>();
            try {
                askService.ask(request.conversationId(), request.question(), resolvedVisitorId,
                        request.timezone(), request.parentTurnId(), new AskService.AskStreamListener() {
                            @Override
                            public void onTurnFinished(String turnId) { completedTurn.set(turnId); }
                            @Override
                            public void onMeta(String conversationId, String traceId, String turnId) {
                                Map<String, Object> meta = new java.util.LinkedHashMap<>();
                                meta.put("conversationId", conversationId);
                                if (traceId != null) {
                                    meta.put("traceId", traceId);
                                }
                                if (turnId != null) {
                                    meta.put("turnId", turnId);
                                }
                                send(emitter, "meta", meta);
                            }

                            @Override
                            public void onProgress(String text) {
                                send(emitter, "progress", Map.of("text", text));
                            }

                            @Override
                            public void onDelta(String text) {
                                send(emitter, "delta", Map.of("text", text));
                            }

                            @Override
                            public void onDone(AskResponse response) {
                                Map<String, Object> done = new java.util.LinkedHashMap<>();
                                done.put("conversationId", response.conversationId());
                                if (response.traceId() != null) {
                                    done.put("traceId", response.traceId());
                                }
                                if (response.turnId() != null) {
                                    done.put("turnId", response.turnId());
                                }
                                done.put("toolCalls", response.toolCalls());
                                done.put("durationMs", response.durationMs());
                                done.put("busy", response.busy());
                                send(emitter, "done", done);
                            }
                        });
            } catch (com.cexpilot.conversation.StaleContextException e) {
                sendQuietly(emitter, "error", e.response());
            } catch (IllegalArgumentException e) {
                sendQuietly(emitter, "error", errorResponse(e.getMessage(), completedTurn.get()));
            } catch (Exception e) {
                log.warn("流式问答失败: {}", e.getMessage());
                sendQuietly(emitter, "error", errorResponse("问答处理失败: " + e.getMessage(), completedTurn.get()));
            } finally {
                emitter.complete();
            }
        });

        return ResponseEntity.ok()
                .headers(headers -> {
                    if (newVisitor) {
                        headers.add(HttpHeaders.SET_COOKIE, visitorCookie(resolvedVisitorId));
                    }
                    // 防止中间层（nginx 等）缓冲 SSE 流
                    headers.add("X-Accel-Buffering", "no");
                })
                .body(emitter);
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 发送失败（多为客户端断开）：中断后续发送，由编排层按失败收尾
            throw new IllegalStateException("SSE 推送失败: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> errorResponse(String message, String completedTurnId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("error", message);
        if (completedTurnId != null) result.put("turnId", completedTurnId);
        return result;
    }

    private static void sendQuietly(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端已断开，error 事件送达不了也无需再试
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
