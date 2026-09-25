package com.cexpilot.time;

import com.cexpilot.time.TimeSpec.AbsolutePoint;
import com.cexpilot.time.TimeSpec.AbsoluteRange;
import com.cexpilot.time.TimeSpec.CalendarPeriod;
import com.cexpilot.time.TimeSpec.CalendarUnit;
import com.cexpilot.time.TimeSpec.DaySegment;
import com.cexpilot.time.TimeSpec.DayTimePoint;
import com.cexpilot.time.TimeSpec.PeriodExtent;
import com.cexpilot.time.TimeSpec.RelativeDayRange;
import com.cexpilot.time.TimeSpec.RollingWindow;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 把 LLM 解析出的 TimeSpec（模糊时间表达）消解为确定的 TimeRange。
 *
 * 相对时间的基准 requestTime 来自构造时注入的 Clock，LLM 不参与当前时间的确定。
 * 任何非法、歧义或不支持的表达直接抛 IllegalArgumentException，
 * 不静默转换为默认时间窗口。
 */
public final class TimeRangeResolver {

    /** 一周的起始日，产品统一配置，当前固定为周一。 */
    private static final DayOfWeek WEEK_START = DayOfWeek.MONDAY;

    /** 各时段边界，产品统一配置，当前为默认划分：[start, end)。 */
    private static final LocalTime EARLY_MORNING_END = LocalTime.of(6, 0);
    private static final LocalTime MORNING_END = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);

    private final Clock clock;

    public TimeRangeResolver(Clock clock) {
        this.clock = clock;
    }

    /**
     * 以注入的 Clock 当前值为基准消解。注意：每次调用都重新读取时钟，
     * 同一次请求内需要一致基准时请用 {@link #resolve(ZoneId, TimeSpec, Instant)}。
     *
     * @param userZone 请求上下文中已确定的用户时区；spec 自带 timezone 时优先使用 spec 的
     * @param spec     LLM 解析出的时间表达
     * @return 确定的查询区间 [startInclusive, endExclusive)
     */
    public TimeRange resolve(ZoneId userZone, TimeSpec spec) {
        return resolve(userZone, spec, clock.instant());
    }

    /**
     * 以调用方固定的 requestTime 为基准消解；同一次请求的所有节点应共用同一个基准。
     *
     * @param userZone    请求上下文中已确定的用户时区；spec 自带 timezone 时优先使用 spec 的
     * @param spec        LLM 解析出的时间表达
     * @param requestTime 请求开始时固定的时间基准（"现在"）
     * @return 确定的查询区间 [startInclusive, endExclusive)
     */
    public TimeRange resolve(ZoneId userZone, TimeSpec spec, Instant requestTime) {
        if (userZone == null || spec == null || requestTime == null) {
            throw new IllegalArgumentException("userZone、spec 和 requestTime 不能为空");
        }
        ZoneId zone = spec.timezone() != null ? spec.timezone() : userZone;

        if (spec instanceof CalendarPeriod p) {
            return resolveCalendarPeriod(p, zone, requestTime);
        }
        if (spec instanceof RollingWindow w) {
            return resolveRollingWindow(w, zone, requestTime);
        }
        if (spec instanceof RelativeDayRange r) {
            return resolveRelativeDayRange(r, zone, requestTime);
        }
        if (spec instanceof AbsoluteRange a) {
            return resolveAbsoluteRange(a, zone, requestTime);
        }
        throw new IllegalArgumentException("不支持的 TimeSpec 类型: " + spec.getClass().getName());
    }

    private TimeRange resolveCalendarPeriod(CalendarPeriod spec, ZoneId zone, Instant requestTime) {
        if (spec.segment() != DaySegment.FULL && spec.unit() != CalendarUnit.DAY) {
            throw new IllegalArgumentException("非 full 时段仅允许 unit=day: " + spec.unit().code());
        }
        if (spec.extent() == PeriodExtent.TO_REQUEST_TIME
                && (spec.offset() != 0 || spec.segment() != DaySegment.FULL)) {
            throw new IllegalArgumentException("to_request_time 仅允许 offset=0 且 segment=full");
        }

        LocalDate requestDate = requestTime.atZone(zone).toLocalDate();
        LocalDate periodStart = shiftPeriod(periodStart(requestDate, spec.unit()), spec.unit(), spec.offset());
        ZonedDateTime start = periodStart.atStartOfDay(zone);
        ZonedDateTime end = shiftPeriod(periodStart, spec.unit(), 1).atStartOfDay(zone);

        if (spec.segment() != DaySegment.FULL) {
            start = periodStart.atTime(segmentStart(spec.segment())).atZone(zone);
            // evening 的结束边界是次日 00:00，不能用 23:59:59 表达
            end = spec.segment() == DaySegment.EVENING
                    ? periodStart.plusDays(1).atStartOfDay(zone)
                    : periodStart.atTime(segmentEnd(spec.segment())).atZone(zone);
        }
        if (spec.extent() == PeriodExtent.TO_REQUEST_TIME) {
            return new TimeRange(start.toInstant(), requestTime, zone);
        }
        return new TimeRange(start.toInstant(), end.toInstant(), zone);
    }

    /** 该日期所在的自然周期起点（week 按配置的 WEEK_START）。 */
    private static LocalDate periodStart(LocalDate date, CalendarUnit unit) {
        return switch (unit) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(WEEK_START));
            case MONTH -> date.withDayOfMonth(1);
            case QUARTER -> date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    /** 自然周期整体平移 amount 个周期；QUARTER 一个周期为 3 个月。 */
    private static LocalDate shiftPeriod(LocalDate periodStart, CalendarUnit unit, long amount) {
        return switch (unit) {
            case DAY -> periodStart.plusDays(amount);
            case WEEK -> periodStart.plusWeeks(amount);
            case MONTH -> periodStart.plusMonths(amount);
            case QUARTER -> periodStart.plusMonths(amount * 3);
            case YEAR -> periodStart.plusYears(amount);
        };
    }

    private static LocalTime segmentStart(DaySegment segment) {
        return switch (segment) {
            case FULL, EARLY_MORNING -> LocalTime.MIDNIGHT;
            case MORNING -> EARLY_MORNING_END;
            case AFTERNOON -> MORNING_END;
            case EVENING -> AFTERNOON_END;
        };
    }

    /** 时段结束边界；EVENING 的结束是次日 00:00，由调用方单独处理，不走这里。 */
    private static LocalTime segmentEnd(DaySegment segment) {
        return switch (segment) {
            case EARLY_MORNING -> EARLY_MORNING_END;
            case MORNING -> MORNING_END;
            case AFTERNOON -> AFTERNOON_END;
            case FULL, EVENING -> throw new IllegalArgumentException("该时段的结束边界不是当天时刻: " + segment.code());
        };
    }

    private TimeRange resolveRollingWindow(RollingWindow spec, ZoneId zone, Instant requestTime) {
        Instant start = requestTime.minus(spec.duration().toDuration());
        return new TimeRange(start, requestTime, zone);
    }

    private TimeRange resolveRelativeDayRange(RelativeDayRange spec, ZoneId zone, Instant requestTime) {
        LocalDate requestDate = requestTime.atZone(zone).toLocalDate();
        ZonedDateTime start = toZoned(requestDate, spec.start(), zone);
        ZonedDateTime end = toZoned(requestDate, spec.end(), zone);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "relative_day_range 的 end 必须晚于 start，跨天请用 day_offset 显式表达: "
                            + "start=" + start + ", end=" + end);
        }
        return new TimeRange(start.toInstant(), end.toInstant(), zone);
    }

    private static ZonedDateTime toZoned(LocalDate requestDate, DayTimePoint point, ZoneId zone) {
        return requestDate.plusDays(point.dayOffset()).atTime(point.time()).atZone(zone);
    }

    private TimeRange resolveAbsoluteRange(AbsoluteRange spec, ZoneId zone, Instant requestTime) {
        LocalDate requestDate = requestTime.atZone(zone).toLocalDate();

        LocalDate startDate = toDate(spec.start(), resolveStartYear(spec.start(), requestDate));
        LocalTime startTime = spec.start().time() != null ? spec.start().time() : LocalTime.MIDNIGHT;
        ZonedDateTime start = startDate.atTime(startTime).atZone(zone);

        ZonedDateTime end = null;
        int endYear = spec.end().year() != null ? spec.end().year() : startDate.getYear();
        // end 缺少年份时：先取 start 的年份，若结束边界不晚于起点则逐年顺推，
        // 从而确定性支持“12 月 30 日到 1 月 2 日”这类跨年表达。
        while (end == null) {
            LocalDate endDate = toDate(spec.end(), endYear);
            ZonedDateTime candidate = switch (spec.endMode()) {
                case INCLUSIVE_DATE -> endDate.plusDays(1).atStartOfDay(zone);
                case EXCLUSIVE -> endDate.atTime(
                        spec.end().time() != null ? spec.end().time() : LocalTime.MIDNIGHT).atZone(zone);
            };
            if (spec.end().year() != null || candidate.isAfter(start)) {
                end = candidate;
            } else {
                endYear++;
            }
        }

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "absolute_range 的结束边界必须晚于起点: start=" + start + ", end=" + end);
        }
        return new TimeRange(start.toInstant(), end.toInstant(), zone);
    }

    /**
     * start 缺少年份时的确定性消解：取 requestTime 所在年份；
     * 若该日期在 requestTime 之后，则取上一年（即最近一次已到来的该日期）。
     */
    private static int resolveStartYear(AbsolutePoint start, LocalDate requestDate) {
        if (start.year() != null) {
            return start.year();
        }
        int year = requestDate.getYear();
        if (toDate(start, year).isAfter(requestDate)) {
            year--;
        }
        return year;
    }

    /** 构造并校验日期，该年月不存在此日期时抛异常（如 2 月 30 日）。 */
    private static LocalDate toDate(AbsolutePoint point, int year) {
        try {
            return LocalDate.of(year, point.month(), point.day());
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException(
                    "不存在的日期: " + year + "-" + point.month() + "-" + point.day(), e);
        }
    }
}
