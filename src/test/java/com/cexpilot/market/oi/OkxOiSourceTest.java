package com.cexpilot.market.oi;

import com.cexpilot.time.OiInterval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R03：6H/12H/1D 必须映射到 UTC 网格变体，与覆盖核对的网格一致。 */
class OkxOiSourceTest {

    @Test
    void longPeriodsUseUtcGridVariants() {
        assertEquals("6Hutc", OkxOiSource.period(OiInterval.SIX_HOURS));
        assertEquals("12Hutc", OkxOiSource.period(OiInterval.TWELVE_HOURS));
        assertEquals("1Dutc", OkxOiSource.period(OiInterval.ONE_DAY));
        // 短周期两种网格一致，保持原样
        assertEquals("5m", OkxOiSource.period(OiInterval.FIVE_MINUTES));
        assertEquals("1H", OkxOiSource.period(OiInterval.ONE_HOUR));
        assertEquals("4H", OkxOiSource.period(OiInterval.FOUR_HOURS));
    }
}
