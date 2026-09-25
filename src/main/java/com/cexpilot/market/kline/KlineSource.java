package com.cexpilot.market.kline;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;

import java.util.List;

/**
 * 交易所 K 线数据源统一契约：提供能力描述，执行查询。
 *
 * 适配器负责参数映射、分页游标推进、单页上限和返回数据归一化，
 * 但不自行宣称业务区间完整——最终是否覆盖目标区间由覆盖核对器判断。
 * 因请求预算、接口错误或游标不再推进而中止时，必须保留中止原因（abortReason），
 * 不能把已取到的部分数据伪装成完整结果。
 */
public interface KlineSource {

    Exchange exchange();

    /** 数据源能力；SeriesQueryPolicy 依据它检查查询要求，不自行维护交易所能力表。 */
    SeriesCapability capability();

    /**
     * 拉取 effective 请求（已经过 SeriesQueryPolicy 对齐）覆盖的 K 线，
     * 结果按 openTime 升序去重，且 openTime 严格落在 [range.start, range.end) 内。
     */
    FetchResult fetch(KlineQueryRequest effective);

    /**
     * @param abortReason 非 null 表示分页被中止（预算用尽、游标不推进等），candles 为部分结果
     */
    record FetchResult(List<Candle> candles, String abortReason) {
    }
}
