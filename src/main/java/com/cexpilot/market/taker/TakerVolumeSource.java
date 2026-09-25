package com.cexpilot.market.taker;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TakerVolumePoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.time.TakerInterval;

import java.util.List;

/**
 * 交易所官方 taker 成交量统计数据源统一契约。与 K 线、持仓量同源约束：
 * 适配器负责参数映射、分页游标和返回数据归一化，但不自行宣称区间完整——
 * 覆盖与否由 SeriesCoverageValidator 按预期序列核对。
 * 因预算或游标异常中止时必须保留 abortReason，不能把部分数据伪装成完整结果。
 */
public interface TakerVolumeSource {

    /** 固定聚合粒度：两所 taker 统计接口都按 5m 周期出数，区间统计在 5m 序列上求和。 */
    TakerInterval INTERVAL = TakerInterval.FIVE_MINUTES;

    Exchange exchange();

    /** 数据源能力（分页预算、历史保留天数）。 */
    SeriesCapability capability();

    /**
     * 拉取周期起点落在 [startMs, endMs) 内的 5m taker 成交量点，按 timestamp 升序去重。
     * 因预算或游标异常中止时保留 abortReason。
     */
    FetchResult fetch(String base, long startMs, long endMs);

    record FetchResult(List<TakerVolumePoint> points, String abortReason) {
    }
}
