package com.cexpilot.trace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只聚合已落库的 LLM 用量，不读取 prompt/response，不依赖 ask 最终成功状态。 */
@Repository
public class CacheStatsRepository {
    private final JdbcTemplate jdbc;

    public CacheStatsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Totals(long promptTokens, long cachedTokens, long measuredCalls, long unknownCalls) {}
    public record Breakdown(Totals total, Map<String, Totals> stages) {}
    private static final Totals ZERO = new Totals(0,0,0,0);
    private record StageTotals(String stage, Totals totals) {}

    public Totals summarize(long startTime, long endTime) {
        return summarizeByStage(startTime,endTime).total();
    }

    public Breakdown summarizeByStage(long startTime, long endTime) {
        // ask_trace.created_at 为秒精度 DATETIME。边界向上取整，精确保留毫秒范围的 [start,end) 语义。
        // FROM_UNIXTIME 在数据库侧转换至会话时区，与写入 created_at 的 NOW() 一致。
        // 不对 created_at 列做函数运算，保留其时间索引的使用机会。
        long startSecond = ceilSecond(startTime);
        long endSecond = ceilSecond(endTime);
        var rows = jdbc.query("""
                SELECT call_stage,
                       COALESCE(SUM(CASE WHEN valid_usage=1 THEN prompt_tokens ELSE 0 END),0) AS prompt_tokens,
                       COALESCE(SUM(CASE WHEN valid_usage=1 THEN cached_tokens ELSE 0 END),0) AS cached_tokens,
                       COALESCE(SUM(valid_usage),0) AS measured_calls,
                       COUNT(*)-COALESCE(SUM(valid_usage),0) AS unknown_calls
                FROM (
                    SELECT e.prompt_tokens,e.cached_tokens,
                           CASE WHEN e.name IN ('router','intent_router','route') THEN 'route'
                                WHEN e.name IN ('dag_planner','monitor_planner','clarification_repair','planner','plan') THEN 'plan'
                                WHEN e.name='answer' THEN 'answer'
                                ELSE 'other' END AS call_stage,
                           CASE WHEN e.prompt_tokens>0 AND e.cached_tokens>=0
                                     AND e.cached_tokens<=e.prompt_tokens THEN 1 ELSE 0 END AS valid_usage
                    FROM ask_trace t JOIN trace_event e ON e.trace_id=t.trace_id
                    WHERE t.created_at>=FROM_UNIXTIME(?) AND t.created_at<FROM_UNIXTIME(?)
                      AND e.event_type='LLM_CALL'
                ) calls
                GROUP BY call_stage
                """, (rs,n)->new StageTotals(rs.getString("call_stage"),new Totals(rs.getLong("prompt_tokens"),rs.getLong("cached_tokens"),
                rs.getLong("measured_calls"),rs.getLong("unknown_calls"))),startSecond,endSecond);
        var stages = new LinkedHashMap<String, Totals>();
        for(String stage:List.of("route","plan","answer","other")) stages.put(stage,ZERO);
        long prompts=0,cached=0,measured=0,unknown=0;
        for(var row:rows) {
            var totals=row.totals(); stages.put(row.stage(),totals);
            prompts+=totals.promptTokens(); cached+=totals.cachedTokens();
            measured+=totals.measuredCalls(); unknown+=totals.unknownCalls();
        }
        // 整体与阶段来自同一次聚合，避免进行中的 ask 在两次 SQL 之间新增用量造成不一致。
        return new Breakdown(new Totals(prompts,cached,measured,unknown),Collections.unmodifiableMap(stages));
    }

    private static long ceilSecond(long milliseconds) {
        return Math.floorDiv(milliseconds,1000)+(Math.floorMod(milliseconds,1000)==0?0:1);
    }
}
