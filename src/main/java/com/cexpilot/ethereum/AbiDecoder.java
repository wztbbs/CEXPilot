package com.cexpilot.ethereum;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 常见 EVM 事件的确定性解码器（Transfer / Approve / Swap 基础识别）。
 * 未识别的 topic0 返回 UNKNOWN，由上层决定如何降级展示。
 */
public final class AbiDecoder {

    public static final String TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    public static final String APPROVAL = "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925";
    public static final String SWAP_V2 = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
    public static final String SWAP_V3 = "0xc42079f94a6350d7e6235f29174924f928cc2ac818eb64fed8004e115fbcca67";
    public static final String WETH_DEPOSIT = "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c";
    public static final String WETH_WITHDRAWAL = "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";

    public static final BigInteger UINT256_MAX = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    /**
     * @param type     TRANSFER / APPROVAL / SWAP_V2 / SWAP_V3 / DEPOSIT / WITHDRAWAL / UNKNOWN
     * @param contract 触发事件的合约地址
     * @param from     事件主体（转出方 / owner / sender），可为 null
     * @param to       事件客体（接收方 / spender），可为 null
     * @param amount   主数量（原始整数，未按 decimals 换算），可为 null
     * @param extra    附加字段：nft tokenId、swap 的各向数量、unlimited 标记等
     */
    public record DecodedEvent(String type, String contract, String from, String to,
                               BigInteger amount, Map<String, String> extra) {
    }

    /**
     * @param topics 含 topic0 的完整 topics 列表
     * @param data   log data（0x 前缀的 hex）
     */
    public static DecodedEvent decode(String contract, List<String> topics, String data) {
        if (topics == null || topics.isEmpty()) {
            return new DecodedEvent("UNKNOWN", contract, null, null, null,
                    Map.of("reason", "no_topics"));
        }
        String topic0 = topics.get(0).toLowerCase();
        return switch (topic0) {
            case TRANSFER -> decodeTransfer(contract, topics, data);
            case APPROVAL -> decodeApproval(contract, topics, data);
            case SWAP_V2 -> decodeSwapV2(contract, topics, data);
            case SWAP_V3 -> decodeSwapV3(contract, topics, data);
            case WETH_DEPOSIT -> new DecodedEvent("DEPOSIT", contract,
                    HexUtils.toAddress(topics.size() > 1 ? topics.get(1) : null), null,
                    HexUtils.toBigInteger(data), Map.of());
            case WETH_WITHDRAWAL -> new DecodedEvent("WITHDRAWAL", contract,
                    HexUtils.toAddress(topics.size() > 1 ? topics.get(1) : null), null,
                    HexUtils.toBigInteger(data), Map.of());
            default -> new DecodedEvent("UNKNOWN", contract, null, null, null,
                    Map.of("topic0", topic0));
        };
    }

    private static DecodedEvent decodeTransfer(String contract, List<String> topics, String data) {
        if (topics.size() >= 4) {
            // ERC721 Transfer(from, to, tokenId)
            return new DecodedEvent("NFT_TRANSFER", contract,
                    HexUtils.toAddress(topics.get(1)), HexUtils.toAddress(topics.get(2)),
                    null, Map.of("tokenId", HexUtils.toBigInteger(topics.get(3)).toString()));
        }
        return new DecodedEvent("TRANSFER", contract,
                HexUtils.toAddress(topics.size() > 1 ? topics.get(1) : null),
                HexUtils.toAddress(topics.size() > 2 ? topics.get(2) : null),
                HexUtils.toBigInteger(data), Map.of());
    }

    private static DecodedEvent decodeApproval(String contract, List<String> topics, String data) {
        // ERC721 的 Approval 有 4 个 topics（tokenId 在 topic3），授权对象是单个 NFT 而非 ERC20 金额
        if (topics.size() >= 4) {
            return new DecodedEvent("NFT_APPROVAL", contract,
                    HexUtils.toAddress(topics.get(1)), HexUtils.toAddress(topics.get(2)),
                    null, Map.of(
                            "tokenId", HexUtils.toBigInteger(topics.get(3)).toString(),
                            "approval_kind", "erc721_single_token"));
        }
        BigInteger value = HexUtils.toBigInteger(data);
        boolean unlimited = value.equals(UINT256_MAX);
        return new DecodedEvent("APPROVAL", contract,
                HexUtils.toAddress(topics.size() > 1 ? topics.get(1) : null),
                HexUtils.toAddress(topics.size() > 2 ? topics.get(2) : null),
                value,
                unlimited ? Map.of("unlimited", "true") : Map.of());
    }

    private static DecodedEvent decodeSwapV2(String contract, List<String> topics, String data) {
        // data = amount0In, amount1In, amount0Out, amount1Out（各 32 字节，token0/1 原始整数）
        String hex = HexUtils.strip0x(data == null ? "" : data);
        if (hex.length() < 256) {
            return new DecodedEvent("SWAP_V2", contract, null, null, null,
                    Map.of("decode_error", "data_too_short"));
        }
        Map<String, String> extra = new LinkedHashMap<>();
        putIfNotNull(extra, "sender", HexUtils.toAddress(topics.size() > 1 ? topics.get(1) : null));
        putIfNotNull(extra, "to", HexUtils.toAddress(topics.size() > 2 ? topics.get(2) : null));
        extra.put("amount0In", HexUtils.toBigInteger("0x" + hex.substring(0, 64)).toString());
        extra.put("amount1In", HexUtils.toBigInteger("0x" + hex.substring(64, 128)).toString());
        extra.put("amount0Out", HexUtils.toBigInteger("0x" + hex.substring(128, 192)).toString());
        extra.put("amount1Out", HexUtils.toBigInteger("0x" + hex.substring(192, 256)).toString());
        extra.put("amount_unit", "raw_integer_token0_token1");
        return new DecodedEvent("SWAP_V2", contract, null, null, null, extra);
    }

    private static DecodedEvent decodeSwapV3(String contract, List<String> topics, String data) {
        // data = amount0(int256), amount1(int256)，带符号；正负号是池子余额变化视角，不是用户钱包收支
        String hex = HexUtils.strip0x(data == null ? "" : data);
        if (hex.length() < 128) {
            return new DecodedEvent("SWAP_V3", contract, null, null, null,
                    Map.of("decode_error", "data_too_short"));
        }
        Map<String, String> extra = new LinkedHashMap<>();
        putIfNotNull(extra, "sender", HexUtils.toAddress(topics.size() > 1 ? topics.get(1) : null));
        putIfNotNull(extra, "recipient", HexUtils.toAddress(topics.size() > 2 ? topics.get(2) : null));
        extra.put("amount0", HexUtils.toSignedInt256("0x" + hex.substring(0, 64)).toString());
        extra.put("amount1", HexUtils.toSignedInt256("0x" + hex.substring(64, 128)).toString());
        extra.put("amount_unit", "raw_integer_token0_token1");
        extra.put("sign_convention", "pool_balance_perspective");
        return new DecodedEvent("SWAP_V3", contract, null, null, null, extra);
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
