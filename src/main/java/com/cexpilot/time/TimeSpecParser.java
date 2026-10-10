package com.cexpilot.time;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalTime;
import java.util.Set;
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
        validateFields(node, switch (TimeSpec.Type.parse(type)) {
            case CALENDAR_PERIOD -> Set.of("type", "timezone", "unit", "offset", "segment", "extent");
            case ROLLING_WINDOW -> Set.of("type", "timezone", "duration");
            case RELATIVE_DAY_RANGE -> Set.of("type", "timezone", "start", "end");
            case ABSOLUTE_RANGE -> Set.of("type", "timezone", "start", "end", "end_mode");
        }, "time");
        ZoneId timezone = parseTimezone(node.get("timezone"));
        return switch (TimeSpec.Type.parse(type)) {
            case CALENDAR_PERIOD -> parseCalendarPeriod(node, timezone);
            case ROLLING_WINDOW -> parseRollingWindow(node, timezone);
            case RELATIVE_DAY_RANGE -> parseRelativeDayRange(node, timezone);
            case ABSOLUTE_RANGE -> parseAbsoluteRange(node, timezone);
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
        validateFields(duration, Set.of("value", "unit"), "duration");
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
        validateFields(node, Set.of("day_offset", "time"), field);
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
        validateFields(node, Set.of("year", "month", "day", "time"), field);
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
        if (!node.isTextual()) throw new IllegalArgumentException("timezone 必须为字符串或 null");
        try {
            return ZoneId.of(node.asText());
        } catch (DateTimeParseException | java.time.zone.ZoneRulesException e) {
            throw new IllegalArgumentException("timezone 必须为 IANA 时区（如 Asia/Shanghai）: " + node.asText());
        }
    }

    private static void validateFields(JsonNode node, Set<String> allowed, String path) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException(path + " 含未知字段: " + field);
        });
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
        boolean fits = "value".equals(field) ? value.canConvertToLong() : value.canConvertToInt();
        if (!fits) throw new IllegalArgumentException(field + " 超出整数范围");
        return value.numberValue();
    }
}
