package com.cexpilot.market.series;

/**
 * 查询区间与 K 线粒度边界不一致时的处理口径。
 */
public enum BoundaryMode {

    /** 精确区间：起止必须对齐粒度边界，否则明确不支持（可换更细粒度或改用 cover）。 */
    EXACT("exact"),
    /** 覆盖区间：允许返回边界外延的 K 线用于观察走势，必须标注实际覆盖范围。 */
    COVER("cover");

    private final String code;

    BoundaryMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static BoundaryMode parse(String code) {
        for (BoundaryMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的边界口径: " + code);
    }
}
