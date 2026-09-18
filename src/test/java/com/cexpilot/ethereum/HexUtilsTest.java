package com.cexpilot.ethereum;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexUtilsTest {

    @Test
    void txHashValidation() {
        assertTrue(HexUtils.isValidTxHash("0x" + "a".repeat(64)));
        assertTrue(HexUtils.isValidTxHash("0x" + "ABCDEF0123456789".repeat(4)));
        assertFalse(HexUtils.isValidTxHash("0x123"));
        assertFalse(HexUtils.isValidTxHash("not-a-hash"));
        assertFalse(HexUtils.isValidTxHash(null));
    }

    @Test
    void weiToEthConversion() {
        assertEquals("1.5", HexUtils.weiToEth(new BigInteger("1500000000000000000")));
        assertEquals("0", HexUtils.weiToEth(BigInteger.ZERO));
        assertEquals("0.00000001", HexUtils.weiToEth(new BigInteger("10000000000")));
    }

    @Test
    void formatTokenAmount() {
        assertEquals("1", HexUtils.formatTokenAmount(new BigInteger("1000000"), 6));
        assertEquals("1234.5678", HexUtils.formatTokenAmount(new BigInteger("1234567800000000000000"), 18));
        assertEquals("1000000", HexUtils.formatTokenAmount(new BigInteger("1000000"), null));
    }

    @Test
    void decodeDynamicString() {
        // ABI 动态 string："USDT"，offset(32B)=0x20, length(32B)=4, data="USDT"
        String offset = String.format("%64s", "20").replace(' ', '0');
        String length = String.format("%64s", "4").replace(' ', '0');
        String data = String.format("%-64s", "55534454").replace(' ', '0');
        assertEquals("USDT", HexUtils.decodeAbiString("0x" + offset + length + data));
    }

    @Test
    void decodeBytes32String() {
        // 某些代币 symbol() 返回 bytes32："WBTC" 右补零
        String data = String.format("%-64s", "57425443").replace(' ', '0');
        assertEquals("WBTC", HexUtils.decodeAbiString("0x" + data));
    }

    @Test
    void decodeEmptyReturnsNull() {
        assertNull(HexUtils.decodeAbiString(null));
        assertNull(HexUtils.decodeAbiString("0x"));
    }

    @Test
    void signedInt256() {
        String minusOne = "0x" + "f".repeat(64);
        assertEquals(BigInteger.valueOf(-1), HexUtils.toSignedInt256(minusOne));
        assertEquals(new BigInteger("100"), HexUtils.toSignedInt256("0x" + String.format("%64s", "64").replace(' ', '0')));
    }
}
