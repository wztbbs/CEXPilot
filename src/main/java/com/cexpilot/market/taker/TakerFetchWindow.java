package com.cexpilot.market.taker;

import java.time.Instant;

/** 适配器内部的取数范围；不改变业务计算范围、预期条数或完整性判断。 */
record TakerFetchWindow(long start, long end) {

    static TakerFetchWindow expand(long start, long end, Instant requestTime, int retentionDays) {
        long interval = TakerVolumeSource.INTERVAL.duration().toMillis();
        long now = requestTime.toEpochMilli();
        long earliest = Math.max(0, now - retentionDays * 86_400_000L);
        return new TakerFetchWindow(Math.max(start - interval, earliest), Math.min(end + interval, now));
    }
}
