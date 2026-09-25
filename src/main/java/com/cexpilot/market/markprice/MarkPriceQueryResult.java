package com.cexpilot.market.markprice;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.time.TimeRange;

import java.util.List;

/**
 * 一次标记/指数价格历史查询的完整结果。
 *
 * @param requested 用户原始要求（区间为 TimeSpec 消解结果，未外扩）
 * @param effective 经 SeriesQueryPolicy 对齐后的实际执行区间（未对齐粒度边界时被外扩）
 * @param candles   通过覆盖核对的价格 K 线
 */
public record MarkPriceQueryResult(PriceType priceType,
                                   TimeRange requested,
                                   TimeRange effective,
                                   List<Candle> candles,
                                   SeriesCoverage coverage) {
}
