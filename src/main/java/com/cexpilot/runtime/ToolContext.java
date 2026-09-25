package com.cexpilot.runtime;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 一次工具调用的上下文：trace 归属 + 对话归属 + 请求时间上下文。
 *
 * @param timezone    请求上下文携带的用户时区；null 表示未携带，工具按各自默认口径回落
 * @param requestTime 本次请求固定的时间基准；null 表示未携带，工具可自行读取时钟（不推荐）
 */
public record ToolContext(String traceId, String conversationId, ZoneId timezone, Instant requestTime) {

    public ToolContext(String traceId, String conversationId) {
        this(traceId, conversationId, null, null);
    }

    public ToolContext(String traceId, String conversationId, ZoneId timezone) {
        this(traceId, conversationId, timezone, null);
    }
}
