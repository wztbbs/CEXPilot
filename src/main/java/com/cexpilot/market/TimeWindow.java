package com.cexpilot.market;

import java.util.Map;

/**
 * 产品支持的时间窗口：1h / 4h / 24h。
 * 窗口越短用越细的 K 线粒度，保证窗口内至少有 12 根K线可供计算。
 */
public record TimeWindow(String code, String interval, String binanceInterval, String okxBar, int candles) {

    private static final Map<String, TimeWindow> WINDOWS = Map.of(
            "1h", new TimeWindow("1h", "5m", "5m", "5m", 12),
            "4h", new TimeWindow("4h", "15m", "15m", "15m", 16),
            "24h", new TimeWindow("24h", "1h", "1h", "1H", 24));

    public static TimeWindow parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return WINDOWS.get("1h");
        }
        TimeWindow window = WINDOWS.get(raw.trim().toLowerCase());
        if (window == null) {
            throw new IllegalArgumentException("不支持的时间窗口: " + raw + "，支持 1h / 4h / 24h");
        }
        return window;
    }
}
