package com.cexpilot.time;

import java.time.Duration;


/**
 * K 线粒度。对外统一使用 code 编码（tool 参数和返回结果），
 * 解析失败直接抛异常，不在这里偷偷补默认值。
 */
public enum CandleInterval implements SeriesInterval {

    FIVE_MINUTES("5m", Duration.ofMinutes(5)),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
    ONE_HOUR("1h", Duration.ofHours(1));

    private final String code;
    private final Duration duration;

    CandleInterval(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    /** 对外统一编码，用于 tool 参数和返回结果。 */
    public String code() {
        return code;
    }

    /** 单根 K 线覆盖的时长；当前枚举仅包含固定时长周期。 */
    public Duration duration() {
        return duration;
    }

    /** 严格解析；缺省值由查询规则决定，不在这里偷偷补默认值。 */
    public static CandleInterval parse(String code) {
        for (CandleInterval interval : values()) {
            if (interval.code.equals(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("不支持的 时间粒度: " + code);
    }
}
