package com.cexpilot.api;

import com.cexpilot.trace.TraceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Trace 回放：用户点 👎 后，能完整回放
 * 当时调了哪些 Tool、拿到什么数据、模型给了什么回答。
 */
@RestController
@RequestMapping("/api")
public class TraceController {

    private final TraceRepository traceRepository;

    public TraceController(TraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    @GetMapping("/trace/{traceId}")
    public ResponseEntity<?> trace(@PathVariable String traceId) {
        Map<String, Object> trace = traceRepository.findTrace(traceId);
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "trace", trace,
                "events", traceRepository.findEvents(traceId)));
    }
}
