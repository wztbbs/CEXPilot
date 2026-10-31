package com.cexpilot.api;

import com.cexpilot.trace.CacheStatsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class CacheStatsController {
    private final CacheStatsRepository repository;
    private final Clock clock;

    public CacheStatsController(CacheStatsRepository repository, Clock clock) {
        this.repository=repository;
        this.clock=clock;
    }

    public record Result(long startTime, long endTime, long promptTokens, long cachedTokens,
                         BigDecimal cacheHitPercent, long measuredCalls, long unknownCalls,
                         Map<String, StageResult> stages) {}
    public record StageResult(long promptTokens, long cachedTokens, BigDecimal cacheHitPercent,
                              long measuredCalls, long unknownCalls) {}

    private static StageResult stageResult(CacheStatsRepository.Totals totals) {
        BigDecimal percent=totals.promptTokens()==0?null:BigDecimal.valueOf(totals.cachedTokens())
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(totals.promptTokens()),2,RoundingMode.HALF_UP);
        return new StageResult(totals.promptTokens(),totals.cachedTokens(),percent,totals.measuredCalls(),totals.unknownCalls());
    }

    @GetMapping("/cache")
    public ResponseEntity<?> cache(@RequestParam long startTime, @RequestParam(required=false) Long endTime) {
        long end=endTime==null?clock.millis():endTime;
        // Unix 毫秒；限制在 MySQL 64 位 FROM_UNIXTIME 支持的时间范围内。
        long max=32536771199000L;
        if(startTime<0 || end<0 || startTime>=max || end>max || startTime>=end)
            return ResponseEntity.badRequest().body(Map.of("error","startTime/endTime 必须为有效 Unix 毫秒时间戳，且 startTime 小于 endTime"));
        var breakdown=repository.summarizeByStage(startTime,end);
        var overall=stageResult(breakdown.total());
        var stages=new LinkedHashMap<String,StageResult>();
        breakdown.stages().forEach((stage,totals)->stages.put(stage,stageResult(totals)));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new Result(startTime,end,
                overall.promptTokens(),overall.cachedTokens(),overall.cacheHitPercent(),overall.measuredCalls(),overall.unknownCalls(),stages));
    }
}
