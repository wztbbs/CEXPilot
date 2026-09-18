package com.cexpilot.ethereum;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * EVM 十六进制数据的确定性解析工具。
 */
public final class HexUtils {

    private HexUtils() {
    }

    public static boolean isValidTxHash(String raw) {
        return raw != null && raw.matches("^0x[0-9a-fA-F]{64}$");
    }

    /** "0x1a2b" -> BigInteger；空 / null -> ZERO。 */
    public static BigInteger toBigInteger(String hex) {
        if (hex == null || hex.isBlank() || "0x".equals(hex)) {
            return BigInteger.ZERO;
        }
        return new BigInteger(strip0x(hex), 16);
    }

    /** 32 字节 word（topic / data 槽）-> 地址（取后 20 字节，小写 0x 前缀）。 */
    public static String toAddress(String word) {
        if (word == null) {
            return null;
        }
        String hex = strip0x(word);
        if (hex.length() < 40) {
            return null;
        }
        return "0x" + hex.substring(hex.length() - 40).toLowerCase();
    }

    /** wei -> ETH，保留 8 位有效小数。 */
    public static String weiToEth(BigInteger wei) {
        if (wei == null || wei.signum() == 0) {
            return "0";
        }
        return new BigDecimal(wei)
                .divide(new BigDecimal("1000000000000000000"), 8, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    /** 按 decimals 格式化 token 数量。 */
    public static String formatTokenAmount(BigInteger raw, Integer decimals) {
        if (raw == null) {
            return null;
        }
        if (decimals == null) {
            return raw.toString();
        }
        return new BigDecimal(raw)
                .movePointLeft(decimals)
                .setScale(Math.min(decimals, 8), RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    /**
     * 解码 eth_call 返回的 string（ABI 动态 string 或 bytes32 两种形态都兼容）。
     */
    public static String decodeAbiString(String hexData) {
        if (hexData == null || hexData.length() < 10) {
            return null;
        }
        String hex = strip0x(hexData);
        try {
            if (hex.length() >= 128) {
                // 动态 string: [offset(32B)] [length(32B)] [data]
                int length = new BigInteger(hex.substring(64, 128), 16).intValue();
                if (length > 0 && length < 256 && hex.length() >= 128 + length * 2L) {
                    return new String(hexToBytes(hex.substring(128, 128 + length * 2))).trim();
                }
            }
            if (hex.length() >= 64) {
                // bytes32 定长符号
                byte[] bytes = hexToBytes(hex.substring(0, 64));
                int end = bytes.length;
                while (end > 0 && bytes[end - 1] == 0) {
                    end--;
                }
                String value = new String(bytes, 0, end).trim();
                return value.isEmpty() ? null : value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** int256 解码：最高位置 1 表示负数。 */
    public static BigInteger toSignedInt256(String word) {
        BigInteger value = toBigInteger(word);
        BigInteger two255 = BigInteger.TWO.pow(255);
        if (value.compareTo(two255) >= 0) {
            return value.subtract(BigInteger.TWO.pow(256));
        }
        return value;
    }

    public static String strip0x(String hex) {
        return hex.startsWith("0x") ? hex.substring(2) : hex;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
