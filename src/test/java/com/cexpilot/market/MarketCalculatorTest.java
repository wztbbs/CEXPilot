package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.OpenInterestInfo.OiPoint;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketCalculatorTest {

    private static Candle candle(long ts, String open, String high, String low, String close) {
        return new Candle(ts, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), BigDecimal.ONE);
    }

    @Test
    void priceChangeUsesFirstOpenToLastClose() {
        List<Candle> candles = List.of(
                candle(1, "100", "105", "99", "104"),
                candle(2, "104", "110", "103", "108"));
        MarketCalculator.PriceChange change = MarketCalculator.priceChange(candles);
        assertEquals(new BigDecimal("100"), change.startPrice());
        assertEquals(new BigDecimal("108"), change.endPrice());
        assertEquals(0, new BigDecimal("8.0000").compareTo(change.changePct()));
        assertEquals(new BigDecimal("110"), change.high());
        assertEquals(new BigDecimal("99"), change.low());
        assertEquals(2, change.candleCount());
    }

    @Test
    void priceChangeEmptyReturnsNull() {
        assertNull(MarketCalculator.priceChange(List.of()));
    }

    @Test
    void fundingTrendRisingFallingFlat() {
        List<BigDecimal> rising = List.of(
                bd("0.0001"), bd("0.0001"), bd("0.0002"), bd("0.0003"));
        assertEquals("rising", MarketCalculator.fundingTrend(rising));

        List<BigDecimal> falling = List.of(
                bd("0.0003"), bd("0.0003"), bd("0.0001"), bd("0.00005"));
        assertEquals("falling", MarketCalculator.fundingTrend(falling));

        List<BigDecimal> flat = List.of(
                bd("0.0001"), bd("0.0001"), bd("0.0001"), bd("0.0001"));
        assertEquals("flat", MarketCalculator.fundingTrend(flat));

        assertEquals("unknown", MarketCalculator.fundingTrend(List.of(bd("0.0001"))));
    }

    @Test
    void oiChangePctFromHistory() {
        List<OiPoint> history = List.of(
                new OiPoint(1, bd("1000")),
                new OiPoint(2, bd("1050")));
        assertEquals(0, new BigDecimal("5.0000")
                .compareTo(MarketCalculator.oiChangePct(history)));
        assertNull(MarketCalculator.oiChangePct(List.of()));
    }

    @Test
    void orderbookImbalanceRatio() {
        OrderBook book = new OrderBook(
                List.of(new OrderBook.Level(bd("100"), bd("2")),
                        new OrderBook.Level(bd("99"), bd("3"))),
                List.of(new OrderBook.Level(bd("101"), bd("1")),
                        new OrderBook.Level(bd("102"), bd("1"))));
        // 买 5 / 卖 2 = 2.5
        assertEquals(0, new BigDecimal("2.5000")
                .compareTo(MarketCalculator.orderbookImbalance(book, 10)));
        assertEquals(new BigDecimal("1"), MarketCalculator.spread(book));
    }

    @Test
    void tradesSummaryBuyRatio() {
        List<Trade> trades = List.of(
                new Trade(1, bd("100"), bd("1"), true),
                new Trade(2, bd("100"), bd("3"), false));
        MarketCalculator.TradesSummary summary = MarketCalculator.tradesSummary(trades);
        assertEquals(2, summary.count());
        assertEquals(1, summary.buyCount());
        assertEquals(0, new BigDecimal("0.2500").compareTo(summary.buyVolumeRatio()));
    }

    @Test
    void divergenceSignificance() {
        MarketCalculator.PriceChange a = new MarketCalculator.PriceChange(
                bd("100"), bd("102"), bd("2.0"), bd("102"), bd("100"), 12);
        MarketCalculator.PriceChange b = new MarketCalculator.PriceChange(
                bd("100"), bd("101"), bd("1.0"), bd("101"), bd("100"), 12);
        MarketCalculator.Divergence d = MarketCalculator.divergence(a, b, new BigDecimal("0.3"));
        assertEquals(0, new BigDecimal("1.0").compareTo(d.diffPct()));
        assertTrue(d.significant());

        MarketCalculator.Divergence same = MarketCalculator.divergence(a, a, new BigDecimal("0.3"));
        assertFalse(same.significant());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
