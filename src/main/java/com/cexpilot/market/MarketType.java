package com.cexpilot.market;

/** 产品维度参与能力校验和数据路由，不能从指标名或 symbol 猜测。 */
public enum MarketType {
    PERPETUAL("perpetual"), SPOT("spot");
    private final String code;
    MarketType(String code) { this.code = code; }
    public String code() { return code; }
    public static MarketType parse(String code) {
        for (var type : values()) if (type.code.equals(code)) return type;
        throw new IllegalArgumentException("不支持的市场类型: " + code + "，仅支持 perpetual/spot");
    }
}
