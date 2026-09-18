package com.cexpilot.market;

public enum Exchange {

    BINANCE("binance"),
    OKX("okx");

    private final String displayName;

    Exchange(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Exchange parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("exchange 不能为空，支持 binance / okx");
        }
        return switch (raw.trim().toLowerCase()) {
            case "binance", "bn", "币安" -> BINANCE;
            case "okx", "okex", "欧易" -> OKX;
            default -> throw new IllegalArgumentException("不支持的 exchange: " + raw + "，支持 binance / okx");
        };
    }
}
