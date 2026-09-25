package com.cexpilot.market.kline;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCoverage;

import java.util.List;

/**
 * 一次 K 线查询的完整结果。
 *
 * @param requested 用户原始要求（区间为 TimeSpec 消解结果，未外扩）
 * @param effective 经 SeriesQueryPolicy 对齐后的实际执行要求（区间未对齐粒度边界时被外扩）
 * @param candles   通过覆盖核对的 K 线
 */
public record KlineQueryResult(KlineQueryRequest requested,
                               KlineQueryRequest effective,
                               List<Candle> candles,
                               SeriesCoverage coverage) {
}
