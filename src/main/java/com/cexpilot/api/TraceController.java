package com.cexpilot.api;

import com.cexpilot.trace.TraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace 回放：用户点 👎 后，能完整回放
 * 当时调了哪些 Tool、拿到什么数据、模型给了什么回答。
 */
@RestController
@RequestMapping("/api")
public class TraceController {

    private static final Logger log = LoggerFactory.getLogger(TraceController.class);

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

    /**
     * 排查用：按时间窗口捞 trace 列表（含每条 trace 的全部事件和反馈）。
     * 例：/api/traces?beginHour=4&endHour=0 捞最近 4 小时；beginHour=12&endHour=8 捞 12 小时前到 8 小时前。
     */
    @GetMapping("/traces")
    public ResponseEntity<?> traces(@RequestParam double beginHour, @RequestParam double endHour) {
        String invalid = validateWindow(beginHour, endHour);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid));
        }
        long beginMinutesAgo = Math.round(beginHour * 60);
        long endMinutesAgo = Math.round(endHour * 60);

        // 窗口在 DB 侧按 DB 时钟计算（created_at 由 DB 写入）；这里取 dbNow 仅为展示窗口边界
        LocalDateTime dbNow = traceRepository.dbNow().toLocalDateTime();
        LocalDateTime begin = dbNow.minusMinutes(beginMinutesAgo);
        LocalDateTime end = dbNow.minusMinutes(endMinutesAgo);

        List<Map<String, Object>> traces = traceRepository.findTracesBetween(beginMinutesAgo, endMinutesAgo);
        log.info("traces 查询: beginHour={}, endHour={}, dbNow={}, 窗口 [{} ~ {}], 命中 {} 条",
                beginHour, endHour, dbNow, begin, end, traces.size());

        List<String> traceIds = traces.stream().map(t -> (String) t.get("trace_id")).toList();
        Map<String, List<Map<String, Object>>> eventsByTrace = traceRepository.findEventsByTraceIds(traceIds);
        Map<String, List<Map<String, Object>>> feedbackByTrace = traceRepository.findFeedbackByTraceIds(traceIds);
        for (Map<String, Object> trace : traces) {
            String traceId = (String) trace.get("trace_id");
            trace.put("events", eventsByTrace.getOrDefault(traceId, List.of()));
            trace.put("feedback", feedbackByTrace.getOrDefault(traceId, List.of()));
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("begin", begin.format(fmt));
        body.put("end", end.format(fmt));
        body.put("count", traces.size());
        body.put("traces", traces);
        return ResponseEntity.ok(body);
    }

    static String validateWindow(double beginHour, double endHour) {
        if (Double.isNaN(beginHour) || Double.isNaN(endHour)
                || Double.isInfinite(beginHour) || Double.isInfinite(endHour)) {
            return "beginHour/endHour 必须是数字";
        }
        if (endHour < 0) {
            return "endHour 不能小于 0";
        }
        if (beginHour <= endHour) {
            return "beginHour 必须大于 endHour（beginHour 表示更早的时间点，如 beginHour=4&endHour=0 表示最近 4 小时）";
        }
        if (beginHour - endHour > 24) {
            return "时间窗口最长 24 小时，请缩小 beginHour 与 endHour 的差值";
        }
        if (beginHour > 24 * 366) {
            return "beginHour 过大";
        }
        return null;
    }
}
