package com.cexpilot.market.markprice;

/**
 * 标记价格或指数价格。
 */
public enum PriceType {

    MARK("mark"),
    INDEX("index");

    private final String code;

    PriceType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static PriceType parse(String code) {
        for (PriceType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的价格类型: " + code);
    }
}
