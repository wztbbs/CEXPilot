package com.cexpilot.time;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * LLM 从用户 query 中解析出的时间表达，4 种互斥结构，每个查询节点只选择其中一种。
 *
 * 公共约定：
 * 1. 所有相对时间共用程序注入的 requestTime，LLM 不填写当前时间或时间戳。
 * 2. timezone 使用 IANA 时区；null 表示继承请求上下文中的已确定时区。
 * 3. 最终由代码（TimeRangeResolver）转换为 [startInclusive, endExclusive)。
 * 4. 非法、歧义或不支持的表达不能静默转换为默认时间窗口。
 */
public sealed interface TimeSpec
        permits TimeSpec.CalendarPeriod, TimeSpec.RollingWindow,
                TimeSpec.RelativeDayRange, TimeSpec.AbsoluteRange {

    /** null 表示继承请求上下文中的已确定时区。 */
    ZoneId timezone();

    /** 自然周期单位。week 的起始日由产品统一配置，不由 LLM 决定。 */
    enum CalendarUnit {
        DAY("day"), WEEK("week"), MONTH("month"), QUARTER("quarter"), YEAR("year");

        private final String code;

        CalendarUnit(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static CalendarUnit parse(String code) {
            for (CalendarUnit unit : values()) {
                if (unit.code.equals(code)) {
                    return unit;
                }
            }
            throw new IllegalArgumentException("不支持的自然周期单位: " + code);
        }
    }

    /** 一天内的时段；非 FULL 仅允许 unit=day。各时段边界由产品统一配置。 */
    enum DaySegment {
        FULL("full"),
        EARLY_MORNING("early_morning"),
        MORNING("morning"),
        AFTERNOON("afternoon"),
        EVENING("evening");

        private final String code;

        DaySegment(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static DaySegment parse(String code) {
            for (DaySegment segment : values()) {
                if (segment.code.equals(code)) {
                    return segment;
                }
            }
            throw new IllegalArgumentException("不支持的时段: " + code);
        }
    }

    /** 周期覆盖范围。 */
    enum PeriodExtent {
        /** 完整周期或完整时段；不代表“截至当前”。 */
        FULL_PERIOD("full_period"),
        /** 从本周期起点至 requestTime；仅允许 offset=0 且 segment=full。 */
        TO_REQUEST_TIME("to_request_time");

        private final String code;

        PeriodExtent(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static PeriodExtent parse(String code) {
            for (PeriodExtent extent : values()) {
                if (extent.code.equals(code)) {
                    return extent;
                }
            }
            throw new IllegalArgumentException("不支持的覆盖范围: " + code);
        }
    }

    /** 滚动窗口单位，均为固定时长：day=24小时，week=168小时。自然月、自然年不在此列。 */
    enum RollingUnit {
        SECOND("second"), MINUTE("minute"), HOUR("hour"), DAY("day"), WEEK("week");

        private final String code;

        RollingUnit(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static RollingUnit parse(String code) {
            for (RollingUnit unit : values()) {
                if (unit.code.equals(code)) {
                    return unit;
                }
            }
            throw new IllegalArgumentException("不支持的滚动窗口单位: " + code);
        }
    }

    /** 明确日期范围的结束边界解释。 */
    enum EndMode {
        /** 包含结束日期整天，仅允许 end.time=null。 */
        INCLUSIVE_DATE("inclusive_date"),
        /** 不包含结束端点；end.time=null 时指结束日期 00:00。 */
        EXCLUSIVE("exclusive");

        private final String code;

        EndMode(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static EndMode parse(String code) {
            for (EndMode mode : values()) {
                if (mode.code.equals(code)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("不支持的结束边界模式: " + code);
        }
    }

    /**
     * 自然周期。例如：昨天、上周、上个月、昨天下午。
     *
     * @param offset  整数，相对于 requestTime 所在的自然周期：0=本周期，-1=上一周期，1=下一周期。
     *                是否允许查询未来由执行前的能力检查决定，这里不拦截。
     * @param segment 非 FULL 仅允许 unit=DAY；FULL 表示整个自然周期，不代表“截至当前”。
     * @param extent  TO_REQUEST_TIME 仅允许 offset=0 且 segment=FULL，
     *                用于“今天截至现在”“本月至今”。
     */
    record CalendarPeriod(ZoneId timezone,
                          CalendarUnit unit,
                          int offset,
                          DaySegment segment,
                          PeriodExtent extent) implements TimeSpec {

        public CalendarPeriod {
            if (unit == null || segment == null || extent == null) {
                throw new IllegalArgumentException("calendar_period 的 unit/segment/extent 不能为空");
            }
        }
    }

    /**
     * 滚动窗口。例如：过去 6 小时、最近 30 分钟。
     * 结束时间固定为 requestTime，开始时间=requestTime-duration；不按整点取整。
     */
    record RollingWindow(ZoneId timezone, FixedDuration duration) implements TimeSpec {

        public RollingWindow {
            if (duration == null) {
                throw new IllegalArgumentException("rolling_window 的 duration 不能为空");
            }
        }
    }

    /**
     * 固定时长数量。“过去 1.5 小时”表达为 value=90、unit=MINUTE。
     *
     * @param value 正整数
     */
    record FixedDuration(long value, RollingUnit unit) {

        public FixedDuration {
            if (unit == null) {
                throw new IllegalArgumentException("duration.unit 不能为空");
            }
            if (value <= 0) {
                throw new IllegalArgumentException("duration.value 必须为正整数: " + value);
            }
        }

        /** 换算为固定时长：day=24小时，week=168小时。 */
        public Duration toDuration() {
            return switch (unit) {
                case SECOND -> Duration.ofSeconds(value);
                case MINUTE -> Duration.ofMinutes(value);
                case HOUR -> Duration.ofHours(value);
                case DAY -> Duration.ofDays(value);
                case WEEK -> Duration.ofDays(value * 7);
            };
        }
    }

    /**
     * 相对日期上的明确时段。例如：昨天 15 点到 17 点。
     * 代码必须检查 end > start；不能因为结束钟点小于开始钟点就自动推断跨天。
     */
    record RelativeDayRange(ZoneId timezone, DayTimePoint start, DayTimePoint end) implements TimeSpec {

        public RelativeDayRange {
            if (start == null || end == null) {
                throw new IllegalArgumentException("relative_day_range 的 start/end 不能为空");
            }
        }
    }

    /**
     * 相对日期 + 当地时刻。
     *
     * @param dayOffset 整数，相对于 requestTime 所在的当地日期：-1=昨天，0=今天；
     *                  按日历日期偏移，不是减去固定 24 小时。
     * @param time      当地时间，范围 00:00:00～23:59:59。
     *                  作为结束边界时不包含该时刻；午夜用下一日期的 00:00:00，不用 24:00:00。
     */
    record DayTimePoint(int dayOffset, LocalTime time) {

        public DayTimePoint {
            if (time == null) {
                throw new IllegalArgumentException("time 不能为空，格式 HH:mm:ss");
            }
        }
    }

    /**
     * 明确日期范围。例如：9 月 1 日到 9 月 3 日。
     * 起止端点分别携带年份，支持跨年范围。
     */
    record AbsoluteRange(ZoneId timezone,
                         AbsolutePoint start,
                         AbsolutePoint end,
                         EndMode endMode) implements TimeSpec {

        public AbsoluteRange {
            if (start == null || end == null || endMode == null) {
                throw new IllegalArgumentException("absolute_range 的 start/end/end_mode 不能为空");
            }
            if (endMode == EndMode.INCLUSIVE_DATE && end.time() != null) {
                throw new IllegalArgumentException("end_mode=inclusive_date 仅允许 end.time=null");
            }
        }
    }

    /**
     * 明确日期端点。
     *
     * @param year  四位年份；用户未明确且上下文无法确定时为 null，
     *              null 交给确定性的年份消解规则处理，不能可靠确定时需澄清。
     * @param month 1～12
     * @param day   1～31；该年月是否存在此日期由消解时校验。
     * @param time  HH:mm:ss，或 null。起点 null 按该日 00:00:00 解释；
     *              结束边界 null 的含义由 end_mode 明确指定。
     */
    record AbsolutePoint(Integer year, int month, int day, LocalTime time) {

        public AbsolutePoint {
            if (year != null && (year < 1000 || year > 9999)) {
                throw new IllegalArgumentException("year 必须为四位年份: " + year);
            }
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException("month 必须在 1～12: " + month);
            }
            if (day < 1 || day > 31) {
                throw new IllegalArgumentException("day 必须在 1～31: " + day);
            }
        }
    }
}
