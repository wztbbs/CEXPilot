package com.cexpilot.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SymbolMapperTest {

    @Test
    void normalizeVariousForms() {
        assertEquals("BTC", SymbolMapper.normalize("BTC"));
        assertEquals("BTC", SymbolMapper.normalize("btc"));
        assertEquals("BTC", SymbolMapper.normalize("BTC-USDT-SWAP"));
        assertEquals("BTC", SymbolMapper.normalize("BTCUSDT"));
        assertEquals("BTC", SymbolMapper.normalize("BTC/USDT"));
        assertEquals("ETH", SymbolMapper.normalize(" eth "));
    }

    @Test
    void rejectInvalid() {
        assertThrows(IllegalArgumentException.class, () -> SymbolMapper.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> SymbolMapper.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> SymbolMapper.normalize("BTC;DROP TABLE"));
    }

    @Test
    void exchangeMapping() {
        assertEquals("BTCUSDT", SymbolMapper.binanceSymbol("BTC"));
        assertEquals("BTC-USDT-SWAP", SymbolMapper.okxInstId("BTC"));
        assertEquals("BTC-USDT", SymbolMapper.okxIndexInstId("BTC"));
    }

    @Test
    void exchangeParse() {
        assertEquals(Exchange.BINANCE, Exchange.parse("Binance"));
        assertEquals(Exchange.BINANCE, Exchange.parse("币安"));
        assertEquals(Exchange.OKX, Exchange.parse("okx"));
        assertThrows(IllegalArgumentException.class, () -> Exchange.parse("bybit"));
    }

    @Test
    void timeWindowParse() {
        assertEquals("5m", TimeWindow.parse("1h").binanceInterval());
        assertEquals("15m", TimeWindow.parse("4h").binanceInterval());
        assertEquals("1H", TimeWindow.parse("24h").okxBar());
        assertEquals("1h", TimeWindow.parse(null).code());
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parse("7d"));
    }
}
