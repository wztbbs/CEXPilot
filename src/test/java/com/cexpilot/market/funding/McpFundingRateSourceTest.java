package com.cexpilot.market.funding;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.FundingRatePoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * McpFundingRateSource 委托行为：exchange/capability/fetch 与本地实现同契约，
 * 传输层（PageFetcher）走注入的 fake，验证"换传输不改语义"。
 */
class McpFundingRateSourceTest {

    private static final long EIGHT_H_MS = 8 * 3_600_000L;
    private static final long G0 = 1_700_006_400_000L;

    @Test
    void delegatesToBinancePaginationLogic() {
        AtomicInteger pageCalls = new AtomicInteger();
        McpFundingRateSource source = new McpFundingRateSource((symbol, startTime, endTime, limit) -> {
            pageCalls.incrementAndGet();
            assertEquals("BTCUSDT", symbol); // 参数映射（BTC → BTCUSDT）仍由本地逻辑负责
            return List.of(
                    new FundingRatePoint(new BigDecimal("0.0001"), G0),
                    new FundingRatePoint(new BigDecimal("0.0002"), G0 + EIGHT_H_MS + 1)); // 抖动应被吸附
        }, s -> EIGHT_H_MS);

        assertEquals(Exchange.BINANCE, source.exchange());
        assertEquals(EIGHT_H_MS, source.fundingIntervalMs("BTC"));

        FundingRateSource.FetchResult result = source.fetch("BTC", EIGHT_H_MS, G0, G0 + 2 * EIGHT_H_MS);
        assertNull(result.abortReason());
        assertEquals(2, result.points().size());
        assertEquals(G0 + EIGHT_H_MS, result.points().get(1).fundingTime());
        assertEquals(1, pageCalls.get()); // 未满页即停
    }

    @Test
    void inferIntervalNormalizesJitterToWholeHours() {
        List<FundingRatePoint> recent = List.of(
                new FundingRatePoint(BigDecimal.ONE, G0),
                new FundingRatePoint(BigDecimal.ONE, G0 + EIGHT_H_MS + 500)); // 500ms 抖动
        assertEquals(EIGHT_H_MS, BinanceFundingRateSource.inferIntervalMs("BTCUSDT", recent));
    }
}
