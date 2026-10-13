package com.cexpilot.dag;

import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.cexpilot.metric.LogicalPlan;
import com.cexpilot.metric.MetricRequest;
import com.cexpilot.metric.TimeQueryParams;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpec;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 继承对账（设计文档 7.2）：new_frame=false 且存在活动 frame 时，逐字段比较
 * 本轮计划与 frame 有效条件的实际 diff，与 Planner 声明的 changes 对账；
 * 声明漏了实际变化的条件 → 返回具体错误进规划修复，修复耗尽则不强行执行。
 *
 * new_frame=true 不做对账（本期旧 frame 在成功提交时被简单覆盖，近期 frame 索引后续再做）。
 */
public final class InheritanceValidator {

    /** 与 DagRuntime 默认用户时区口径一致。 */
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private final TimeRangeResolver timeResolver = new TimeRangeResolver(Clock.systemUTC());

    /**
     * @return 对账失败的修复文案；一致、无活动 frame 或 frame 无 targets 时返回 null
     */
    public String check(QueryFrame frame, CompiledPlan plan, Set<String> declaredChanges, RequestContext requestContext) {
        if (frame == null || frame.targets().isEmpty() || requestContext == null) {
            return null;
        }
        Set<String> actualChanges = diffKeys(frame, plan, requestContext);
        Set<String> missing = new TreeSet<>(actualChanges);
        missing.removeAll(declaredChanges);
        if (missing.isEmpty()) {
            return null;
        }
        return "changes 声明与计划不一致：相对活动查询状态，计划实际变化了 " + missing
                + " 但未在 changes 中声明。若这是用户本轮明确修改的条件，在输出 JSON 的顶层加入 "
                + "\"changes\":" + missing + " 后重新输出完整 JSON；"
                + "否则把上述条件恢复为与活动查询状态一致的值";
    }

    /**
     * frame vs 本轮计划的实际变化条件键集合（R10 配对对账）：
     * 先按目标节点配对（同所同币种 > 同币种 > 双边各只有一个目标时直接配对），再逐对比较节点内条件；
     * 配不上任何 frame 目标的计划目标视为新增、未被认领的 frame 目标视为删除，其
     * exchange/base/metric 均计入变化。此前按字段集合比较，"币安BTC+OKX ETH → 币安ETH+OKX BTC"
     * 这类配对互换两侧集合完全相同，会漏检。
     */
    Set<String> diffKeys(QueryFrame frame, CompiledPlan plan, RequestContext requestContext) {
        List<EffectiveTarget> from = new java.util.ArrayList<>(frame.targets());
        List<EffectiveTarget> to = plan.targets();
        boolean singlePair = to.size() == 1 && from.size() == 1;
        Set<String> changed = new TreeSet<>();
        for (EffectiveTarget planTarget : to) {
            EffectiveTarget match = popMatch(from, planTarget, singlePair);
            if (match == null) {
                changed.add("exchange");
                changed.add("base");
                changed.add("metric");
                continue;
            }
            comparePair(changed, "exchange", match.exchange(), planTarget.exchange());
            comparePair(changed, "base", baseOf(match), baseOf(planTarget));
            comparePair(changed, "metric", match.metric(), planTarget.metric());
            comparePair(changed, "shape", match.queryShape(), planTarget.queryShape());
            comparePair(changed, "interval", intervalOf(match), intervalOf(planTarget));
            comparePair(changed, "include_unclosed",
                    String.valueOf(match.includeUnclosed()), String.valueOf(planTarget.includeUnclosed()));
            comparePair(changed, "count", String.valueOf(match.count()), String.valueOf(planTarget.count()));
            comparePair(changed, "depth", String.valueOf(match.depth()), String.valueOf(planTarget.depth()));
        }
        if (!from.isEmpty()) {
            changed.add("exchange");
            changed.add("base");
            changed.add("metric");
        }
        if (timeChanged(frame, plan, requestContext)) {
            changed.add("time");
        }
        return changed;
    }

    /**
     * 配对并从待配列表认领：同所同币种 > 同所（任意币种，R10——配对互换时把变化暴露成 base 漏报，
     * 同币优先会把互换吸收成 exchange 单键变化，模型声明 exchange 即可蒙混）> 同币种 >
     * 双边各只有一个目标时直接配对。
     */
    private static EffectiveTarget popMatch(List<EffectiveTarget> remaining, EffectiveTarget planTarget,
                                            boolean singlePair) {
        EffectiveTarget sameExchangeBase = null;
        EffectiveTarget sameExchange = null;
        EffectiveTarget sameBase = null;
        for (EffectiveTarget candidate : remaining) {
            if (candidate.exchange().equals(planTarget.exchange())) {
                if (baseOf(candidate).equals(baseOf(planTarget))) {
                    sameExchangeBase = candidate;
                    break;
                }
                if (sameExchange == null) {
                    sameExchange = candidate;
                }
                continue;
            }
            if (sameBase == null && baseOf(candidate).equals(baseOf(planTarget))) {
                sameBase = candidate;
            }
        }
        EffectiveTarget match = sameExchangeBase != null ? sameExchangeBase
                : sameExchange != null ? sameExchange : sameBase;
        if (match == null && singlePair && remaining.size() == 1) {
            match = remaining.get(0);
        }
        if (match != null) {
            remaining.remove(match);
        }
        return match;
    }

    private static void comparePair(Set<String> changed, String key, String from, String to) {
        if (!java.util.Objects.equals(from, to)) {
            changed.add(key);
        }
    }

    private static String baseOf(EffectiveTarget target) {
        return target.instrument().path("base").asText();
    }

    private static String intervalOf(EffectiveTarget target) {
        return target.interval() == null ? "AUTO" : target.interval();
    }

    /**
     * 时间比较按消解后的绝对区间比（Planner 可能返回等价的 calendar_period 或 absolute_range），
     * 不是 JSON 逐字比。滚动窗口（recent 6h 这类）时长相同视为同一条件延续——集合随"现在"滚动，
     * 跨轮消解必然得到不同区间，不能算用户修改。
     */
    private boolean timeChanged(QueryFrame frame, CompiledPlan plan, RequestContext requestContext) {
        Map<String, TimeSpec> planSpecs = planSpecsByGroup(plan.logicalPlan());
        for (EffectiveTarget target : plan.targets()) {
            if (target.timeExpression() == null) {
                continue;
            }
            EffectiveTarget frameTarget = matchFrameTarget(frame.targets(), target);
            if (frameTarget == null || frameTarget.timeExpression() == null) {
                // 找不到可继承的对应节点：exchange/base 变化已在各自键上体现，时间不再单独计
                continue;
            }
            JsonNode frameTime = frameTarget.timeExpression();
            JsonNode planTime = target.timeExpression();
            if (frameTime.equals(planTime) || sameRollingWindow(frameTime, planTime)) {
                continue;
            }
            TimeSpec planSpec = planSpecs.get(target.groupId());
            if (planSpec == null) {
                continue;
            }
            try {
                TimeRange frameRange = timeResolver.resolve(frameZone(frame),
                        TimeSpecParser.parse(frameTime), Instant.parse(frame.requestTime()));
                TimeRange planRange = timeResolver.resolve(
                        requestContext.userZone() != null ? requestContext.userZone() : DEFAULT_ZONE,
                        planSpec, requestContext.requestTime());
                if (!frameRange.startInclusive().equals(planRange.startInclusive())
                        || !frameRange.endExclusive().equals(planRange.endExclusive())) {
                    return true;
                }
            } catch (Exception e) {
                // 时间无法消解时保守处理：不算对账差异，由执行期的时间校验兜底
                continue;
            }
        }
        return false;
    }

    /** 为计划节点找 frame 中可继承的对应节点：同所同币种 > 同所 > 同币种 > frame 唯一节点（与 popMatch 同序）。 */
    private static EffectiveTarget matchFrameTarget(List<EffectiveTarget> frameTargets, EffectiveTarget planTarget) {
        String base = planTarget.instrument().path("base").asText();
        EffectiveTarget sameExchangeBase = null;
        EffectiveTarget sameExchange = null;
        EffectiveTarget sameBase = null;
        for (EffectiveTarget candidate : frameTargets) {
            if (candidate.exchange().equals(planTarget.exchange())) {
                if (sameExchange == null) {
                    sameExchange = candidate;
                }
                if (candidate.instrument().path("base").asText().equals(base)) {
                    sameExchangeBase = candidate;
                    break;
                }
                continue;
            }
            if (sameBase == null && candidate.instrument().path("base").asText().equals(base)) {
                sameBase = candidate;
            }
        }
        if (sameExchangeBase != null) {
            return sameExchangeBase;
        }
        if (sameExchange != null) {
            return sameExchange;
        }
        if (sameBase != null) {
            return sameBase;
        }
        return frameTargets.size() == 1 ? frameTargets.get(0) : null;
    }

    private static boolean sameRollingWindow(JsonNode a, JsonNode b) {
        return "rolling_window".equals(a.path("type").asText())
                && "rolling_window".equals(b.path("type").asText())
                && a.path("duration").equals(b.path("duration"));
    }

    private static ZoneId frameZone(QueryFrame frame) {
        return frame.timezone() != null ? ZoneId.of(frame.timezone()) : DEFAULT_ZONE;
    }

    private static Map<String, TimeSpec> planSpecsByGroup(LogicalPlan logicalPlan) {
        Map<String, TimeSpec> specs = new java.util.HashMap<>();
        for (MetricRequest metric : logicalPlan.metrics()) {
            if (metric.params() instanceof TimeQueryParams p) {
                specs.put(metric.id(), p.time());
            }
        }
        return specs;
    }
}
