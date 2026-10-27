package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.time.OiInterval;
import com.cexpilot.time.TimeRange;

/**
 * 一次持仓量历史查询的完整要求。经过 SeriesQueryPolicy 检查后，
 * 序列 range 可外扩到网格；端点统计的滚动窗口整体平移至最近采样边界，保持时长。
 * range 的 endExclusive 在 BOUNDARY_VALUES 模式表示逻辑终点，选样包含该时刻；
 * 物理请求的 +1ms 仅存在于 Service 内，不进入逻辑窗口。
 *
 * @param includeUnclosed 是否允许包含当前未完结周期的采样点；false 时剔除并标注
 */
public record OiQueryRequest(Exchange exchange,
                             String base,
                             OiInterval interval,
                             TimeRange range,
                             boolean includeUnclosed,
                             OiQueryMode mode) {
    public OiQueryRequest(Exchange exchange, String base, OiInterval interval, TimeRange range, boolean includeUnclosed) {
        this(exchange, base, interval, range, includeUnclosed, OiQueryMode.INTERVAL_SAMPLES);
    }
}
