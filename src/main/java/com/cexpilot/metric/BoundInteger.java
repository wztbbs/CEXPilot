package com.cexpilot.metric;

/**
 * 带默认值的整数范围；用于 count / depth 等可选参数的绑定声明。
 */
public record BoundInteger(int min, int max, Integer defaultValue) {
    public BoundInteger {
        if (min < 1 || min > max) throw new IllegalArgumentException("范围必须满足 1 <= min <= max");
        if (defaultValue != null && (defaultValue < min || defaultValue > max))
            throw new IllegalArgumentException("default 必须位于 min/max 范围内");
    }

    public boolean contains(int value) {
        return value >= min && value <= max;
    }
}
