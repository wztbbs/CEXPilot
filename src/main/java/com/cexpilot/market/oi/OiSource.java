package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.OiInterval;

import java.util.List;

/**
 * 交易所持仓量历史数据源统一契约。与 K 线同源约束：适配器负责参数映射、
 * 分页游标和返回数据归一化，但不自行宣称区间完整——覆盖与否由
 * SeriesCoverageValidator 按预期采样序列核对。
 */
public interface OiSource {

    Exchange exchange();

    /** 数据源能力（支持的采样粒度、分页预算、历史保留天数）。 */
    SeriesCapability capability();

    /**
     * 拉取采样时刻落在 [startMs, endMs) 内的持仓量采样点，按 timestamp 升序去重。
     * 因预算或游标异常中止时保留 abortReason。
     */
    FetchResult fetch(String base, OiInterval interval, long startMs, long endMs);

    record FetchResult(List<OiPoint> points, String abortReason) {
    }
}
