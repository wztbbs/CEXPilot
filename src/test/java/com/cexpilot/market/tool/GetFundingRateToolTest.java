package com.cexpilot.market.tool;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.FundingInfo;
import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * get_funding_rate 快照：只输出当前费率与结算时间，不含历史序列与统计。
 */
class GetFundingRateToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void snapshotContainsNoHistoryOrStatistics() throws Exception {
        MarketDataService market = mock(MarketDataService.class);
        when(market.fundingSnapshot(Exchange.BINANCE, "BTC")).thenReturn(new FundingInfo(
                new BigDecimal("0.0001"), "settled", 1_700_035_200_000L,
                1_700_064_000_000L, 0, 1_700_000_000_000L));
        ToolResult result = new GetFundingRateTool(market).execute(
                MAPPER.readTree("{\"exchange\":\"binance\",\"symbol\":\"BTC\"}"), null);
        assertTrue(result.ok(), () -> String.valueOf(result.error()));
        var facts = result.data();
        assertEquals("0.0001", facts.path("current_funding_rate").asText());
        assertEquals("0.01", facts.path("current_funding_rate_pct").asText());
        assertEquals("settled", facts.path("current_rate_kind").asText());
        assertTrue(facts.path("current_rate_settlement_time_utc8").asText().startsWith("2023-"));
        assertTrue(facts.path("next_funding_time_utc8").asText().startsWith("2023-"));
        assertFalse(facts.has("recent_rates"));
        assertFalse(facts.has("trend"));
        assertFalse(facts.has("count"));
        assertFalse(facts.has("rates"));
    }
}
