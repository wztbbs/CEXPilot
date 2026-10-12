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

    /** frame vs 本轮计划的实际变化条件键集合。 */
    Set<String> diffKeys(QueryFrame frame, CompiledPlan plan, RequestContext requestContext) {
        List<EffectiveTarget> from = frame.targets();
        List<EffectiveTarget> to = plan.targets();
        Set<String> changed = new TreeSet<>();
        compare(changed, "exchange", from, to, EffectiveTarget::exchange);
        compare(changed, "base", from, to, t -> t.instrument().path("base").asText());
        compare(changed, "metric", from, to, EffectiveTarget::metric);
        compare(changed, "shape", from, to, EffectiveTarget::queryShape);
        compare(changed, "interval", from, to, t -> t.interval() == null ? "AUTO" : t.interval());
        compare(changed, "include_unclosed", from, to, t -> String.valueOf(t.includeUnclosed()));
        compare(changed, "count", from, to, t -> String.valueOf(t.count()));
        compare(changed, "depth", from, to, t -> String.valueOf(t.depth()));
        if (timeChanged(frame, plan, requestContext)) {
            changed.add("time");
        }
        return changed;
    }

    private static void compare(Set<String> changed, String key,
                                List<EffectiveTarget> from, List<EffectiveTarget> to,
                                Function<EffectiveTarget, String> value) {
        Set<String> a = from.stream().map(value).collect(Collectors.toCollection(TreeSet::new));
        Set<String> b = to.stream().map(value).collect(Collectors.toCollection(TreeSet::new));
        if (!a.equals(b)) {
            changed.add(key);
        }
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

    /** 为计划节点找 frame 中可继承的对应节点：优先同交易所同币种，其次同币种，再次 frame 唯一节点。 */
    private static EffectiveTarget matchFrameTarget(List<EffectiveTarget> frameTargets, EffectiveTarget planTarget) {
        String base = planTarget.instrument().path("base").asText();
        EffectiveTarget sameBase = null;
        for (EffectiveTarget candidate : frameTargets) {
            if (candidate.exchange().equals(planTarget.exchange())
                    && candidate.instrument().path("base").asText().equals(base)) {
                return candidate;
            }
            if (candidate.instrument().path("base").asText().equals(base)) {
                sameBase = candidate;
            }
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
