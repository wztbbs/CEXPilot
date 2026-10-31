package com.cexpilot.trace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只聚合已落库的 LLM 用量，不读取 prompt/response，不依赖 ask 最终成功状态。 */
@Repository
public class CacheStatsRepository {
    private final JdbcTemplate jdbc;

    public CacheStatsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Totals(long promptTokens, long cachedTokens, long measuredCalls, long unknownCalls) {}

    public Totals summarize(long startTime, long endTime) {
        // ask_trace.created_at 为秒精度 DATETIME。边界向上取整，精确保留毫秒范围的 [start,end) 语义。
        // FROM_UNIXTIME 在数据库侧转换至会话时区，与写入 created_at 的 NOW() 一致。
        // 不对 created_at 列做函数运算，保留其时间索引的使用机会。
        long startSecond = ceilSecond(startTime);
        long endSecond = ceilSecond(endTime);
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN valid_usage=1 THEN prompt_tokens ELSE 0 END),0) AS prompt_tokens,
                       COALESCE(SUM(CASE WHEN valid_usage=1 THEN cached_tokens ELSE 0 END),0) AS cached_tokens,
                       COALESCE(SUM(valid_usage),0) AS measured_calls,
                       COUNT(*)-COALESCE(SUM(valid_usage),0) AS unknown_calls
                FROM (
                    SELECT e.prompt_tokens,e.cached_tokens,
                           CASE WHEN e.prompt_tokens>0 AND e.cached_tokens>=0
                                     AND e.cached_tokens<=e.prompt_tokens THEN 1 ELSE 0 END AS valid_usage
                    FROM ask_trace t JOIN trace_event e ON e.trace_id=t.trace_id
                    WHERE t.created_at>=FROM_UNIXTIME(?) AND t.created_at<FROM_UNIXTIME(?)
                      AND e.event_type='LLM_CALL'
                ) calls
                """, (rs,n)->new Totals(rs.getLong("prompt_tokens"),rs.getLong("cached_tokens"),
                rs.getLong("measured_calls"),rs.getLong("unknown_calls")),startSecond,endSecond);
    }

    private static long ceilSecond(long milliseconds) {
        return Math.floorDiv(milliseconds,1000)+(Math.floorMod(milliseconds,1000)==0?0:1);
    }
}
