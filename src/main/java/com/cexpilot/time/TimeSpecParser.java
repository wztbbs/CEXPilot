package com.cexpilot.time;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * LLM 输出的 JSON → TimeSpec 的唯一入口。
 * 缺字段、非法枚举值、非法格式一律抛 IllegalArgumentException（消息带字段名），
 * 不静默补默认值。
 */
public final class TimeSpecParser {

    private TimeSpecParser() {
    }

    public static TimeSpec parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("time 必须为对象");
        }
        String type = text(node, "type", true);
        ZoneId timezone = parseTimezone(node.get("timezone"));
        return switch (type) {
            case "calendar_period" -> parseCalendarPeriod(node, timezone);
            case "rolling_window" -> parseRollingWindow(node, timezone);
            case "relative_day_range" -> parseRelativeDayRange(node, timezone);
            case "absolute_range" -> parseAbsoluteRange(node, timezone);
            default -> throw new IllegalArgumentException("不支持的 time.type: " + type);
        };
    }

    private static TimeSpec.CalendarPeriod parseCalendarPeriod(JsonNode node, ZoneId timezone) {
        return new TimeSpec.CalendarPeriod(
                timezone,
                TimeSpec.CalendarUnit.parse(text(node, "unit", true)),
                integer(node, "offset", true).intValue(),
                TimeSpec.DaySegment.parse(text(node, "segment", true)),
                TimeSpec.PeriodExtent.parse(text(node, "extent", true)));
    }

    private static TimeSpec.RollingWindow parseRollingWindow(JsonNode node, ZoneId timezone) {
        JsonNode duration = object(node, "duration");
        return new TimeSpec.RollingWindow(timezone, new TimeSpec.FixedDuration(
                integer(duration, "value", true).longValue(),
                TimeSpec.RollingUnit.parse(text(duration, "unit", true))));
    }

    private static TimeSpec.RelativeDayRange parseRelativeDayRange(JsonNode node, ZoneId timezone) {
        return new TimeSpec.RelativeDayRange(timezone,
                parseDayTimePoint(object(node, "start"), "start"),
                parseDayTimePoint(object(node, "end"), "end"));
    }

    private static TimeSpec.DayTimePoint parseDayTimePoint(JsonNode node, String field) {
        try {
            return new TimeSpec.DayTimePoint(
                    integer(node, "day_offset", true).intValue(),
                    LocalTime.parse(text(node, "time", true)));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + ".time 格式必须为 HH:mm:ss: " + node.path("time").asText());
        }
    }

    private static TimeSpec.AbsoluteRange parseAbsoluteRange(JsonNode node, ZoneId timezone) {
        return new TimeSpec.AbsoluteRange(timezone,
                parseAbsolutePoint(object(node, "start"), "start"),
                parseAbsolutePoint(object(node, "end"), "end"),
                TimeSpec.EndMode.parse(text(node, "end_mode", true)));
    }

    private static TimeSpec.AbsolutePoint parseAbsolutePoint(JsonNode node, String field) {
        Integer year = node.hasNonNull("year") ? integer(node, "year", false).intValue() : null;
        LocalTime time = null;
        if (node.hasNonNull("time")) {
            try {
                time = LocalTime.parse(node.get("time").asText());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(field + ".time 格式必须为 HH:mm:ss: " + node.get("time").asText());
            }
        }
        return new TimeSpec.AbsolutePoint(year,
                integer(node, "month", true).intValue(),
                integer(node, "day", true).intValue(),
                time);
    }

    private static ZoneId parseTimezone(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return ZoneId.of(node.asText());
        } catch (DateTimeParseException | java.time.zone.ZoneRulesException e) {
            throw new IllegalArgumentException("timezone 必须为 IANA 时区（如 Asia/Shanghai）: " + node.asText());
        }
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("缺少对象字段: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            if (required) {
                throw new IllegalArgumentException("缺少字符串字段: " + field);
            }
            return null;
        }
        return value.asText();
    }

    private static Number integer(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            if (required) {
                throw new IllegalArgumentException("缺少整数字段: " + field);
            }
            return null;
        }
        return value.numberValue();
    }
}
