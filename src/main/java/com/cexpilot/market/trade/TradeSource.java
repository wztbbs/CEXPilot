package com.cexpilot.market.trade;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.TradePoint;
import com.cexpilot.market.series.SeriesCapability;

import java.util.List;

/**
 * 交易所成交历史数据源统一契约。
 * 逐笔成交没有预期序列（笔数不可预知），适配器负责参数映射、分页游标、
 * 单位换算（OKX 张→币），中止时保留 abortReason；
 * 完整性由 TradeQueryService 以「分页自然结束 + 去重 + 边界」判定。
 */
public interface TradeSource {

    Exchange exchange();

    /** 数据源能力（分页预算、历史保留天数）。 */
    SeriesCapability capability();

    /** 拉取成交时刻落在 [startMs, endMs) 内的成交，按时间升序去重。 */
    FetchResult fetch(String base, long startMs, long endMs);

    record FetchResult(List<TradePoint> trades, String abortReason) {
    }
}
