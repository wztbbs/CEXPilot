package com.cexpilot.market.oi;

/** 序列/极值使用半开区间内采样；期初/期末/变化使用含两端点的观测窗口。 */
public enum OiQueryMode {
    INTERVAL_SAMPLES("interval_samples"),
    BOUNDARY_VALUES("boundary_values");

    private final String code;
    OiQueryMode(String code) { this.code = code; }
    public String code() { return code; }
}
