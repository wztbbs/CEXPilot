package com.cexpilot.market.funding;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.policy.SampleQueryPolicy;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.series.SeriesCoverageValidator;
import com.cexpilot.market.series.SeriesValidation;
import com.cexpilot.market.series.TimePoint;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpec;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织资金费率历史查询流程：消解时间 → 确定结算周期 → 预算检查 →
 * 分页拉取 → 覆盖核对。预期序列 = 结算时刻网格 ⊂ [start, end)，
 * 与 K 线同一套 SeriesCoverageValidator（以周期起点表达结算点）。
 */
@Service
public class FundingQueryService {

    private final Map<Exchange, FundingRateSource> sources;
    private final TimeRangeResolver timeRangeResolver;

    public FundingQueryService(List<FundingRateSource> sources, TimeRangeResolver timeRangeResolver) {
        Map<Exchange, FundingRateSource> map = new EnumMap<>(Exchange.class);
        for (FundingRateSource source : sources) {
            map.put(source.exchange(), source);
        }
        this.sources = map;
        this.timeRangeResolver = timeRangeResolver;
    }

    /**
     * @param userZone    请求上下文时区；spec 自带 timezone 时优先
     * @param spec        LLM 解析出的时间表达
     * @param requestTime 本次请求固定的时间基准，时间消解与未结算判定共用
     */
    public FundingQueryResult query(ZoneId userZone, TimeSpec spec, Instant requestTime,
                                    Exchange exchange, String base) {
        TimeRange range = timeRangeResolver.resolve(userZone, spec, requestTime);
        FundingRateSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无资金费率数据源: " + exchange.displayName());
        }
        long intervalMs = source.fundingIntervalMs(base);
        long startMs = range.startInclusive().toEpochMilli();
        long endMs = range.endExclusive().toEpochMilli();

        long periodsNeeded = (endMs - startMs) / intervalMs + 1;
        if (periodsNeeded > source.capability().budget()) {
            throw new IllegalArgumentException(
                    "区间过长：按 " + (intervalMs / 3_600_000L) + "h 结算周期约需 " + periodsNeeded
                            + " 期，超过单次查询预算 " + source.capability().budget() + " 期；请缩短区间");
        }

        FundingRateSource.FetchResult fetch = source.fetch(base, intervalMs, startMs, endMs);
        // 一个“当前周期”不能代表全部历史：相邻结算间隔与当前周期不一致时，
        // 可能是周期变更（如 8h 调整 4h）或数据缺失，无法用单一网格核对，响亮失败
        assertConsistentSpacing(fetch.points(), intervalMs);

        // 结算点以周期起点表达（fundingTime - intervalMs），与 K 线共用同一覆盖核对：
        // 区间平移一个周期后，“结算时刻 ∈ [start,end)” ⟺ “周期起点 ∈ [start-int,end-int)”。
        // 注意 beyond-now 判断必须用未平移的原始终点，否则未来结算会被误判为区间完整
        List<TimePoint> points = fetch.points().stream()
                .map(p -> new TimePoint(p.fundingTime() - intervalMs, Boolean.TRUE))
                .toList();
        SeriesValidation validation = SeriesCoverageValidator.validate(
                SeriesCoverageValidator.PointSemantics.PERIOD,
                intervalMs, startMs - intervalMs, endMs - intervalMs,
                false, points, fetch.abortReason(), requestTime, endMs);

        Map<Long, FundingRatePoint> byPeriodStart = new HashMap<>();
        for (FundingRatePoint p : fetch.points()) {
            byPeriodStart.putIfAbsent(p.fundingTime() - intervalMs, p);
        }
        List<FundingRatePoint> accepted = validation.points().stream()
                .map(tp -> byPeriodStart.get(tp.ms()))
                .toList();
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("区间内没有已结算的资金费率");
        }
        return new FundingQueryResult(range, accepted, validation.coverage(), intervalMs);
    }

    /**
     * 最近 N 期已结算费率（样本语义）：按当前周期估算回溯窗口，不足 N 期时逐次扩窗
     * （周期可能发生过调整，count×当前周期的估算可能取不够）。不跑覆盖核对；
     * 扩窗后仍不足 N 期（合约较新）时返回实际取得的全部，由调用方标注。
     */
    public FundingRecentResult queryRecent(Exchange exchange, String base, int count, Instant requestTime) {
        SampleQueryPolicy.check(count, 100);
        FundingRateSource source = sources.get(exchange);
        if (source == null) {
            throw new IllegalArgumentException("交易所暂无资金费率数据源: " + exchange.displayName());
        }
        long intervalMs = source.fundingIntervalMs(base);
        long nowMs = requestTime.toEpochMilli();
        List<FundingRatePoint> points = List.of();
        long lookbackMs = (long) count * intervalMs * 2;
        long maxLookbackMs = source.capability().budget() * intervalMs;
        while (lookbackMs <= maxLookbackMs) {
            FundingRateSource.FetchResult fetch = source.fetch(
                    base, intervalMs, nowMs - lookbackMs, nowMs);
            if (fetch.abortReason() != null) {
                throw new IllegalArgumentException("资金费率样本拉取中止，无法确认最近 "
                        + count + " 期完整性: " + fetch.abortReason());
            }
            points = fetch.points();
            if (points.size() >= count || lookbackMs == maxLookbackMs) {
                break;
            }
            lookbackMs = Math.min(lookbackMs * 2, maxLookbackMs);
        }
        if (points.size() > count) {
            points = points.subList(points.size() - count, points.size());
        }
        return new FundingRecentResult(points, intervalMs);
    }

    /** 相邻结算间隔必须与当前周期一致（容差同结算时间网格吸附）。 */
    private static void assertConsistentSpacing(List<FundingRatePoint> points, long intervalMs) {
        for (int i = 1; i < points.size(); i++) {
            long diff = points.get(i).fundingTime() - points.get(i - 1).fundingTime();
            if (Math.abs(diff - intervalMs) > 60_000L) {
                throw new IllegalArgumentException(
                        "该合约在查询区间内的结算间隔不一致（当前周期 "
                                + (intervalMs / 3_600_000L) + "h，实测间隔 " + (diff / 3_600_000.0)
                                + "h）：可能发生过结算周期调整或存在数据缺失，暂不支持完整覆盖核对");
            }
        }
    }
}
