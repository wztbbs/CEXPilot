package com.cexpilot.metric;

/** 指标查询形态；形态码只在这里定义，未来 recent_n 形态在 requiresTime 上留口。 */
public enum QueryShape {
    RANGE_STATISTIC("range_statistic"),
    TIME_SERIES("time_series"),
    SNAPSHOT("snapshot"),
    OFFICIAL_24H("official_24h"),
    RECENT_N("recent_n");

    private final String code;

    QueryShape(String code) { this.code = code; }

    public String code() { return code; }

    public static QueryShape from(String code) {
        for (QueryShape shape : values()) {
            if (shape.code.equals(code)) return shape;
        }
        throw new IllegalArgumentException("不支持的查询形态: " + code);
    }

    /** 区间与序列形态要求 time；快照与交易所官方滚动 24h 口径不接受 time 与粒度。 */
    public boolean requiresTime() { return this == RANGE_STATISTIC || this == TIME_SERIES; }

    /** 交易所官方口径形态：窗口由交易所定义，不能与用户时间窗口混用。 */
    public boolean isExchangeWindow() { return this == OFFICIAL_24H; }

    /** 最近 N 期形态：按期数取样，不是时间区间。 */
    public boolean requiresCount() { return this == RECENT_N; }

    public boolean isSeries() { return this == TIME_SERIES; }
}
