package com.cexpilot.market.series;

import com.cexpilot.time.SeriesInterval;

import java.util.Set;

/**
 * 一个历史序列数据源的能力描述；SeriesQueryPolicy 依据它检查查询要求，
 * 调用方不自行维护交易所能力表。
 *
 * @param supportedIntervals 支持的粒度集合（无粒度概念的域传空集）
 * @param pageLimit          单页上限（根/条）
 * @param maxPages           单次查询的最大分页页数；请求预算 = pageLimit × maxPages
 * @param retentionDays      数据保留天数（如币安持仓量历史只保留最近 30 天）；
 *                           null 表示不限历史深度
 */
public record SeriesCapability(Set<? extends SeriesInterval> supportedIntervals,
                               int pageLimit, int maxPages, Integer retentionDays) {

    public SeriesCapability(Set<? extends SeriesInterval> supportedIntervals,
                            int pageLimit, int maxPages) {
        this(supportedIntervals, pageLimit, maxPages, null);
    }

    public long budget() {
        return (long) pageLimit * maxPages;
    }
}
