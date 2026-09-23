package com.cexpilot.ethereum;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbiDecoderTest {

    private static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    private static final String ALICE = "0x0000000000000000000000001111111111111111111111111111111111111111";
    private static final String BOB = "0x0000000000000000000000002222222222222222222222222222222222222222";

    private static String word64(String hexWithoutPrefix) {
        return String.format("%64s", hexWithoutPrefix).replace(' ', '0');
    }

    @Test
    void decodeErc20Transfer() {
        // 转 1000000 (= 1 USDT，6 位小数)
        String data = "0x" + word64("f4240");
        AbiDecoder.DecodedEvent event = AbiDecoder.decode(USDT,
                List.of(AbiDecoder.TRANSFER, ALICE, BOB), data);

        assertEquals("TRANSFER", event.type());
        assertEquals("0x1111111111111111111111111111111111111111", event.from());
        assertEquals("0x2222222222222222222222222222222222222222", event.to());
        assertEquals(new BigInteger("1000000"), event.amount());
    }

    @Test
    void decodeUnlimitedApproval() {
        String data = "0x" + "f".repeat(64);
        AbiDecoder.DecodedEvent event = AbiDecoder.decode(USDT,
                List.of(AbiDecoder.APPROVAL, ALICE, BOB), data);

        assertEquals("APPROVAL", event.type());
        assertEquals("0x2222222222222222222222222222222222222222", event.to());
        assertEquals("true", event.extra().get("unlimited"));
    }

    @Test
    void decodeNormalApproval() {
        String data = "0x" + word64("f4240");
        AbiDecoder.DecodedEvent event = AbiDecoder.decode(USDT,
                List.of(AbiDecoder.APPROVAL, ALICE, BOB), data);
        assertEquals("APPROVAL", event.type());
        assertTrue(event.extra().isEmpty());
        assertEquals(new BigInteger("1000000"), event.amount());
    }

    @Test
    void decodeSwapV2() {
        String data = "0x"
                + word64("64")          // amount0In = 100
                + word64("0")           // amount1In = 0
                + word64("0")           // amount0Out = 0
                + word64("c8");         // amount1Out = 200
        AbiDecoder.DecodedEvent event = AbiDecoder.decode("0xpair",
                List.of(AbiDecoder.SWAP_V2, ALICE, BOB), data);

        assertEquals("SWAP_V2", event.type());
        assertEquals("100", event.extra().get("amount0In"));
        assertEquals("200", event.extra().get("amount1Out"));
        // sender/to 命名保留，不泛称 from；数量标注为 token0/1 原始整数
        assertNull(event.from());
        assertEquals("0x1111111111111111111111111111111111111111", event.extra().get("sender"));
        assertEquals("0x2222222222222222222222222222222222222222", event.extra().get("to"));
        assertEquals("raw_integer_token0_token1", event.extra().get("amount_unit"));
    }

    @Test
    void decodeSwapV3SignedAmounts() {
        String negative = "f".repeat(63) + "0"; // -16 的 int256 补码
        String data = "0x" + negative + word64("64");
        AbiDecoder.DecodedEvent event = AbiDecoder.decode("0xpool",
                List.of(AbiDecoder.SWAP_V3, ALICE, BOB), data);

        assertEquals("SWAP_V3", event.type());
        assertEquals("-16", event.extra().get("amount0"));
        assertEquals("100", event.extra().get("amount1"));
        // topic[1]/[2] 解码为 sender/recipient，不与代币地址混淆
        assertEquals("0x1111111111111111111111111111111111111111", event.extra().get("sender"));
        assertEquals("0x2222222222222222222222222222222222222222", event.extra().get("recipient"));
        assertEquals("raw_integer_token0_token1", event.extra().get("amount_unit"));
        assertEquals("pool_balance_perspective", event.extra().get("sign_convention"));
    }

    @Test
    void nftApprovalKeepsTokenId() {
        String tokenIdTopic = "0x" + word64("3039"); // tokenId 12345
        AbiDecoder.DecodedEvent event = AbiDecoder.decode("0xnft",
                List.of(AbiDecoder.APPROVAL, ALICE, BOB, tokenIdTopic), "0x");
        assertEquals("NFT_APPROVAL", event.type());
        assertEquals("12345", event.extra().get("tokenId"));
        assertEquals("erc721_single_token", event.extra().get("approval_kind"));
        assertNull(event.amount());
    }

    @Test
    void unknownTopicFallsBack() {
        AbiDecoder.DecodedEvent event = AbiDecoder.decode("0xwhatever",
                List.of("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"), "0x");
        assertEquals("UNKNOWN", event.type());
        assertTrue(event.extra().containsKey("topic0"));
    }

    @Test
    void nftTransferDetectedByTopicCount() {
        String tokenIdTopic = "0x" + word64("3039"); // tokenId 12345
        AbiDecoder.DecodedEvent event = AbiDecoder.decode("0xnft",
                List.of(AbiDecoder.TRANSFER, ALICE, BOB, tokenIdTopic), "0x");
        assertEquals("NFT_TRANSFER", event.type());
        assertEquals("12345", event.extra().get("tokenId"));
    }
}
