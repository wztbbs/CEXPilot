package com.cexpilot.market;

/**
 * Symbol 标准化：
 * 用户说 "BTC" / "btc" / "BTC-USDT" / "BTCUSDT"，统一规范为基础币 "BTC"，
 * 再映射成各交易所的合约代码。V0.1 使用 USDⓈ-M / USDT 永续合约口径。
 */
public final class SymbolMapper {

    private SymbolMapper() {
    }

    /** 归一化为大写基础币代码，如 BTC / ETH。 */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("symbol 不能为空，例如 BTC / ETH");
        }
        String s = raw.trim().toUpperCase()
                .replace("-USDT-SWAP", "")
                .replace("-USDT", "")
                .replace("USDT", "")
                .replace("-", "")
                .replace("/", "");
        if (!s.matches("^[A-Z0-9]{2,15}$")) {
            throw new IllegalArgumentException("无法识别的 symbol: " + raw);
        }
        return s;
    }

    /** Binance USDⓈ-M 永续：BTC -> BTCUSDT */
    public static String binanceSymbol(String base) {
        return base + "USDT";
    }

    /** OKX USDT 永续：BTC -> BTC-USDT-SWAP */
    public static String okxInstId(String base) {
        return base + "-USDT-SWAP";
    }

    /** OKX 指数行情用现货代码：BTC -> BTC-USDT */
    public static String okxIndexInstId(String base) {
        return base + "-USDT";
    }
}
