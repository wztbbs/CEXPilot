package com.cexpilot.market.series;

import java.util.List;

/**
 * 覆盖核对的输出：通过核对的数据点 + 覆盖结果。
 *
 * @param points 时间戳落在预期序列内的数据点（升序）；错位、重复、被剔除的未完结点不在其中
 */
public record SeriesValidation(List<TimePoint> points, SeriesCoverage coverage) {
}
