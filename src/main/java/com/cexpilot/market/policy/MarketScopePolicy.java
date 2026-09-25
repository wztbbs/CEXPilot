package com.cexpilot.market.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.regex.Pattern;

/** USDT 永续行情工具共用的输入检查。显式指定的不支持值必须拒绝，不能改成默认市场。 */
public final class MarketScopePolicy {
    private static final Pattern UNSUPPORTED_PAIR = Pattern.compile("[A-Z0-9]+(?:USDC|BUSD|USD)");

    private MarketScopePolicy() {}

    public static void check(JsonNode args) {
        if (args == null || !args.isObject()) {
            throw new IllegalArgumentException("args 必须是对象");
        }
        String quote = text(args, "quote_asset", "USDT").toUpperCase(Locale.ROOT);
        String market = text(args, "market_type", "perpetual").toLowerCase(Locale.ROOT);
        if (!"USDT".equals(quote) || !"perpetual".equals(market)) {
            throw new IllegalArgumentException("当前行情工具仅支持 USDT 本位永续合约，不支持 "
                    + quote + " / " + market + "；不会替换交易对或市场");
        }
        // 兼容既有 symbol 写法，但不能把 BTC-USDC 等交易对当作基础币拼接成 USDT 合约。
        String symbol = text(args, "symbol", "").toUpperCase(Locale.ROOT);
        String[] parts = symbol.split("[-/]", -1);
        if (parts.length > 1) {
            if (!"USDT".equals(parts[1]) || parts.length > 3
                    || parts.length == 3 && !"SWAP".equals(parts[2])) {
                throw new IllegalArgumentException("不支持的交易对或合约: " + symbol + "，仅支持 USDT 永续");
            }
        } else if (UNSUPPORTED_PAIR.matcher(symbol).matches()) {
            throw new IllegalArgumentException("不支持的交易对: " + symbol + "，请分别指定基础币和 quote_asset");
        }
    }

    private static String text(JsonNode args, String field, String defaultValue) {
        JsonNode value = args.get(field);
        if (value == null) return defaultValue;
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return value.textValue().trim();
    }
}
