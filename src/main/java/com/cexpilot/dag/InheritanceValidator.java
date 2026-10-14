package com.cexpilot.dag;

import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.TimeRange;
import com.cexpilot.time.TimeRangeResolver;
import com.cexpilot.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
     * 先按目标节点配对（未变条件优先，再按同所同币种 > 同所 > 同币种匹配），再逐对比较节点内条件；
     * 配不上任何 frame 目标的计划目标视为新增、未被认领的 frame 目标视为删除，其
     * exchange/base/metric 均计入变化。此前按字段集合比较，"币安BTC+OKX ETH → 币安ETH+OKX BTC"
     * 这类配对互换两侧集合完全相同，会漏检。
     */
    Set<String> diffKeys(QueryFrame frame, CompiledPlan plan, RequestContext requestContext) {
        List<EffectiveTarget> from = new java.util.ArrayList<>(frame.targets());
        List<EffectiveTarget> to = plan.targets();
        List<EffectiveTarget> matches = matchTargets(from, to);
        for (EffectiveTarget match : matches) if (match != null) from.remove(match);
        Set<String> changed = new TreeSet<>();
        for (int i = 0; i < to.size(); i++) {
            EffectiveTarget planTarget = to.get(i);
            EffectiveTarget match = matches.get(i);
            if (match == null) {
                // 同身份重复组豁免：配不上的目标若与某个已配对目标的查询身份逐字段全等，
                // 是同一查询的取数模式差异（pinned 旧快照 + latest 新取数的"比刚才涨了多少"
                // 新旧对比对），不是用户条件变化——glm/deepseek-v4.1/deepseek-0731 三模型
                // 在该场景全部被误判漏报 changes，每次白烧 57~127s 修复轮（trace 实证）。
                // 条件不同的真新增对象不受豁免，照旧打回。
                if (hasMatchedSameIdentity(planTarget, to, matches, i)) {
                    continue;
                }
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
            if (timeChanged(frame, match, planTarget, requestContext)) changed.add("time");
        }
        if (!from.isEmpty()) { changed.add("exchange"); changed.add("base"); changed.add("metric"); }
        return changed;
    }

    /** 来源继承与实际 diff 共用同一套目标配对，不按单独字段集合猜测来源对象。 */
    static List<EffectiveTarget> matchTargets(List<EffectiveTarget> previous, List<EffectiveTarget> to) {
        List<EffectiveTarget> from = new java.util.ArrayList<>(previous);
        boolean singlePair = to.size() == 1 && from.size() == 1;
        // 先认领完全未变的目标，避免前面的变化节点抢走后面的正确配对。
        EffectiveTarget[] matches = new EffectiveTarget[to.size()];
        for (int i = 0; i < to.size(); i++) {
            EffectiveTarget planTarget = to.get(i);
            EffectiveTarget exact = from.stream().filter(t -> sameConditions(t, planTarget))
                    .findFirst().orElse(null);
            if (exact != null) {
                matches[i] = exact;
                from.remove(exact);
            }
        }
        for (int i = 0; i < to.size(); i++) {
            if (matches[i] == null) {
                matches[i] = popMatch(from, to.get(i), singlePair);
            }
        }
        return java.util.Arrays.asList(matches);
    }

    /**
     * 配对并从待配列表认领：同所同币种 > 同所（任意币种，R10——配对互换时把变化暴露成 base 漏报，
     * 同币优先会把互换吸收成 exchange 单键变化，模型声明 exchange 即可蒙混）> 同币种 >
     * 双边各只有一个目标时直接配对。
     */
    private static EffectiveTarget popMatch(List<EffectiveTarget> remaining, EffectiveTarget planTarget,
                                            boolean singlePair) {
        EffectiveTarget match = null;
        int best = 0;
        for (EffectiveTarget candidate : remaining) {
            boolean sameExchange = candidate.exchange().equals(planTarget.exchange());
            boolean sameBase = baseOf(candidate).equals(baseOf(planTarget));
            int rank = sameExchange && sameBase ? 300 : sameExchange ? 200 : sameBase ? 100 : 0;
            if (rank == 0) {
                continue;
            }
            // 同所同币的多个指标，优先匹配相同指标/形态，再用条件消除同指标歧义。
            if (candidate.metric().equals(planTarget.metric())) rank += 30;
            if (candidate.queryShape().equals(planTarget.queryShape())) rank += 10;
            if (java.util.Objects.equals(candidate.timeExpression(), planTarget.timeExpression())) rank += 5;
            if (java.util.Objects.equals(candidate.interval(), planTarget.interval())) rank += 2;
            if (rank > best) {
                match = candidate;
                best = rank;
            }
        }
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

    /**
     * 未配对目标是否存在"已配对成功的同身份目标"：exchange/instrument/metric/形态/时间/
     * interval/includeUnclosed/count/depth 逐字段全等（sameConditions 口径）。
     * reference_mode（pinned/latest）不在 EffectiveTarget 上，豁免按纯查询身份判定——
     * 同身份的重复组无论取数模式如何都不构成条件漂移。
     */
    private static boolean hasMatchedSameIdentity(EffectiveTarget planTarget, List<EffectiveTarget> to,
                                                  List<EffectiveTarget> matches, int self) {
        for (int j = 0; j < to.size(); j++) {
            if (j == self || matches.get(j) == null) {
                continue;
            }
            if (sameConditions(planTarget, to.get(j))) {
                return true;
            }
        }
        return false;
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
    boolean timeChanged(QueryFrame frame, EffectiveTarget from, EffectiveTarget to,
                                RequestContext requestContext) {
        return timeChanged(frame, from, to, requestContext, false);
    }

    /** 来源必须有可证明的等价条件；消解失败不能当成继承成立。 */
    boolean sameTimeRequirement(QueryFrame frame, EffectiveTarget from, EffectiveTarget to, RequestContext context) {
        return !timeChanged(frame, from, to, context, true);
    }

    private boolean timeChanged(QueryFrame frame, EffectiveTarget from, EffectiveTarget to,
                                RequestContext requestContext, boolean changedOnFailure) {
        JsonNode frameTime = from.timeExpression();
        JsonNode planTime = to.timeExpression();
        if (frameTime == null || planTime == null) {
            return !java.util.Objects.equals(frameTime, planTime);
        }
        if (frameTime.equals(planTime) || sameRollingWindow(frameTime, planTime)) {
            return false;
        }
        try {
            TimeRange frameRange = timeResolver.resolve(frameZone(frame),
                    TimeSpecParser.parse(frameTime), Instant.parse(frame.requestTime()));
            TimeRange planRange = timeResolver.resolve(
                    requestContext.userZone() != null ? requestContext.userZone() : DEFAULT_ZONE,
                    TimeSpecParser.parse(planTime), requestContext.requestTime());
            return !frameRange.startInclusive().equals(planRange.startInclusive())
                    || !frameRange.endExclusive().equals(planRange.endExclusive());
        } catch (Exception e) {
            // 无法消解时保持现有策略，由执行期时间校验处理。
            return changedOnFailure;
        }
    }

    private static boolean sameConditions(EffectiveTarget a, EffectiveTarget b) {
        return a.exchange().equals(b.exchange()) && a.instrument().equals(b.instrument())
                && a.metric().equals(b.metric()) && a.queryShape().equals(b.queryShape())
                && java.util.Objects.equals(a.timeExpression(), b.timeExpression())
                && java.util.Objects.equals(a.interval(), b.interval())
                && java.util.Objects.equals(a.includeUnclosed(), b.includeUnclosed())
                && java.util.Objects.equals(a.count(), b.count())
                && java.util.Objects.equals(a.depth(), b.depth());
    }

    private static boolean sameRollingWindow(JsonNode a, JsonNode b) {
        return "rolling_window".equals(a.path("type").asText())
                && "rolling_window".equals(b.path("type").asText())
                && a.path("duration").equals(b.path("duration"));
    }

    private static ZoneId frameZone(QueryFrame frame) {
        return frame.timezone() != null ? ZoneId.of(frame.timezone()) : DEFAULT_ZONE;
    }

}
