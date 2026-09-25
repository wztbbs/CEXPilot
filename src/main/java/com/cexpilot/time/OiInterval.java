package com.cexpilot.time;

import java.time.Duration;

/**
 * 持仓量采样粒度。对外统一使用 code 编码（与币安 period 参数一致），
 * 各交易所实际支持的子集由各自数据源 capability 声明。
 */
public enum OiInterval implements SeriesInterval {

    FIVE_MINUTES("5m", Duration.ofMinutes(5)),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
    THIRTY_MINUTES("30m", Duration.ofMinutes(30)),
    ONE_HOUR("1h", Duration.ofHours(1)),
    TWO_HOURS("2h", Duration.ofHours(2)),
    FOUR_HOURS("4h", Duration.ofHours(4)),
    SIX_HOURS("6h", Duration.ofHours(6)),
    TWELVE_HOURS("12h", Duration.ofHours(12)),
    ONE_DAY("1d", Duration.ofDays(1));

    private final String code;
    private final Duration duration;

    OiInterval(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Duration duration() {
        return duration;
    }

    /** 严格解析；缺省值由查询规则决定，不在这里偷偷补默认值。 */
    public static OiInterval parse(String code) {
        for (OiInterval interval : values()) {
            if (interval.code.equals(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("不支持的采样粒度: " + code);
    }
}
