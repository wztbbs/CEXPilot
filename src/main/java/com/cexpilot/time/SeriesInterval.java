package com.cexpilot.time;

import java.time.Duration;

/**
 * 历史序列的粒度抽象：K 线、持仓量采样、标记价格等各域的粒度枚举都实现它，
 * 使 SeriesQueryPolicy / SeriesCoverageValidator 不绑定具体域。
 */
public interface SeriesInterval {

    /** 对外统一编码，用于 tool 参数和返回结果。 */
    String code();

    /** 单个序列点覆盖的时长；仅支持固定时长周期。 */
    Duration duration();
}
