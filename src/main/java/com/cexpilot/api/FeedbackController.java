package com.cexpilot.api;

import com.cexpilot.feedback.FeedbackRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 答案底部的 👍 / 👎 反馈，👎 可带错误分类与文字补充。
 */
@RestController
@RequestMapping("/api")
public class FeedbackController {

    private static final Set<String> CATEGORIES = Set.of(
            "data_error", "reasoning_error", "missing_info",
            "not_understood", "context_error", "tool_error", "other");

    private final FeedbackRepository repository;

    public FeedbackController(FeedbackRepository repository) {
        this.repository = repository;
    }

    public record FeedbackRequest(String traceId, String rating, String category, String comment) {
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@RequestBody FeedbackRequest request) {
        if (request.traceId() == null || request.traceId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "traceId 不能为空"));
        }
        if (!"up".equals(request.rating()) && !"down".equals(request.rating())) {
            return ResponseEntity.badRequest().body(Map.of("error", "rating 只能是 up / down"));
        }
        String category = request.category();
        if (category != null && !CATEGORIES.contains(category)) {
            category = "other";
        }
        repository.save(request.traceId(), request.rating(), category, request.comment());
        return ResponseEntity.ok(Map.of("saved", true));
    }
}
