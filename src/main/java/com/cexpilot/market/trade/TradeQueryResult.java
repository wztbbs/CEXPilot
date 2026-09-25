package com.cexpilot.market.trade;

import com.cexpilot.market.model.TradePoint;
import com.cexpilot.time.TimeRange;

import java.util.List;

/**
 * 一次成交历史查询的完整结果。
 * 逐笔成交没有预期序列：complete = 分页自然结束（无中止）且数据通过去重与边界校验。
 *
 * @param abortReason 分页中止原因；非 null 时结果为区间的部分数据
 */
public record TradeQueryResult(TimeRange range,
                               List<TradePoint> trades,
                               boolean complete,
                               String abortReason) {
}
