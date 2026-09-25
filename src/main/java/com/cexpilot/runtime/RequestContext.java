package com.cexpilot.runtime;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 一次问答请求的时间上下文：在请求开始时确定一次，随后贯穿规划与执行，
 * 保证同一次请求里所有工具节点共用同一个时间基准。
 *
 * @param userZone    请求携带的用户时区；null 表示未携带，由工具按默认口径回落
 * @param requestTime 固定的时间基准（"现在"）；所有相对时间消解、未收盘判定都以它为准，
 *                    不允许各环节各自读取时钟
 */
public record RequestContext(ZoneId userZone, Instant requestTime) {
}
