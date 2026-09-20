package com.cexpilot.market;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 毫秒时间戳 → 可读时间（UTC+8）。模型对裸 ms 时间戳的换算是纯算术但高频出错
 * （年份、小时都错过），所有进 facts 的时间统一在 tool 出口渲染成字符串。
 */
public final class Times {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.ofHours(8));

    private Times() {
    }

    /** 如 "2026-09-20 16:00:00"；调用方负责在字段名或列说明里标注 UTC+8。 */
    public static String readable(long epochMs) {
        return FMT.format(Instant.ofEpochMilli(epochMs));
    }
}
