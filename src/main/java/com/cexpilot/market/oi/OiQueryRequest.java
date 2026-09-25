package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.time.OiInterval;
import com.cexpilot.time.TimeRange;

/**
 * 一次持仓量历史查询的完整要求。经过 SeriesQueryPolicy 检查后，
 * range 可能已被外扩到粒度边界（effective request）。
 *
 * @param includeUnclosed 是否允许包含当前未完结周期的采样点；false 时剔除并标注
 */
public record OiQueryRequest(Exchange exchange,
                             String base,
                             OiInterval interval,
                             TimeRange range,
                             boolean includeUnclosed) {
}
