package com.cexpilot.time;

import java.time.Duration;

/**
 * taker 成交量统计的聚合粒度。交易所官方统计接口（币安 takerlongshortRatio /
 * OKX taker-volume-contract）固定取最细粒度 5m，区间统计在 5m 序列上求和。
 */
public enum TakerInterval implements SeriesInterval {

    FIVE_MINUTES("5m", Duration.ofMinutes(5));

    private final String code;
    private final Duration duration;

    TakerInterval(String code, Duration duration) {
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
}
