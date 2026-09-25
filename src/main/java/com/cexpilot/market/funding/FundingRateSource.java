package com.cexpilot.market.funding;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.series.SeriesCapability;

import java.util.List;

/**
 * 交易所资金费率历史数据源统一契约。
 * 与 K 线同源约束：适配器负责参数映射、分页游标、结算时间网格吸附，
 * 但不自行宣称区间完整——覆盖与否由 SeriesCoverageValidator 按预期结算序列核对。
 */
public interface FundingRateSource {

    Exchange exchange();

    /** 数据源能力（单页上限、分页页数预算）；SeriesQueryPolicy 依据它检查查询要求。 */
    SeriesCapability capability();

    /** 该合约的结算周期（毫秒），每次查询实时获取，不做缓存。 */
    long fundingIntervalMs(String base);

    /**
     * 拉取结算时刻落在 [startMs, endMs) 内的已结算费率，按 fundingTime 升序去重。
     * 结算时间偏离网格在容差内时吸附归位；因预算或游标异常中止时保留 abortReason。
     */
    FetchResult fetch(String base, long intervalMs, long startMs, long endMs);

    record FetchResult(List<FundingRatePoint> points, String abortReason) {
    }
}
