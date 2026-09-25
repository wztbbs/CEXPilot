package com.cexpilot.market.series;

/**
 * 历史序列的一个数据点（时间位置 + 完结状态），覆盖核对的通用输入。
 *
 * @param ms        数据点起始时间戳（毫秒），如 K 线开盘时间、采样时刻
 * @param confirmed 数据源标记的完结状态：true=已完结，false=未完结（数据仍是部分值），
 *                  null=数据源不提供，由核对器按时间推断
 */
public record TimePoint(long ms, Boolean confirmed) {

    public TimePoint(long ms) {
        this(ms, null);
    }
}
