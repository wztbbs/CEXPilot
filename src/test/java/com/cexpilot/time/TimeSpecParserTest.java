package com.cexpilot.time;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeSpecParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void calendarPeriod() {
        TimeSpec spec = TimeSpecParser.parse(json(
                "{\"type\":\"calendar_period\",\"timezone\":null,\"unit\":\"day\",\"offset\":-1,"
                        + "\"segment\":\"afternoon\",\"extent\":\"full_period\"}"));
        TimeSpec.CalendarPeriod p = assertInstanceOf(TimeSpec.CalendarPeriod.class, spec);
        assertNull(p.timezone());
        assertEquals(TimeSpec.CalendarUnit.DAY, p.unit());
        assertEquals(-1, p.offset());
        assertEquals(TimeSpec.DaySegment.AFTERNOON, p.segment());
        assertEquals(TimeSpec.PeriodExtent.FULL_PERIOD, p.extent());
    }

    @Test
    void rollingWindow() {
        TimeSpec spec = TimeSpecParser.parse(json(
                "{\"type\":\"rolling_window\",\"timezone\":\"Asia/Shanghai\","
                        + "\"duration\":{\"value\":90,\"unit\":\"minute\"}}"));
        TimeSpec.RollingWindow w = assertInstanceOf(TimeSpec.RollingWindow.class, spec);
        assertEquals(ZoneId.of("Asia/Shanghai"), w.timezone());
        assertEquals(90, w.duration().value());
        assertEquals(TimeSpec.RollingUnit.MINUTE, w.duration().unit());
    }

    @Test
    void relativeDayRange() {
        TimeSpec spec = TimeSpecParser.parse(json(
                "{\"type\":\"relative_day_range\",\"timezone\":null,"
                        + "\"start\":{\"day_offset\":-1,\"time\":\"15:00:00\"},"
                        + "\"end\":{\"day_offset\":0,\"time\":\"01:00:00\"}}"));
        TimeSpec.RelativeDayRange r = assertInstanceOf(TimeSpec.RelativeDayRange.class, spec);
        assertEquals(-1, r.start().dayOffset());
        assertEquals(LocalTime.of(15, 0), r.start().time());
        assertEquals(0, r.end().dayOffset());
        assertEquals(LocalTime.of(1, 0), r.end().time());
    }

    @Test
    void absoluteRange() {
        TimeSpec spec = TimeSpecParser.parse(json(
                "{\"type\":\"absolute_range\",\"timezone\":null,"
                        + "\"start\":{\"year\":null,\"month\":9,\"day\":1,\"time\":null},"
                        + "\"end\":{\"year\":2026,\"month\":9,\"day\":3,\"time\":null},"
                        + "\"end_mode\":\"inclusive_date\"}"));
        TimeSpec.AbsoluteRange a = assertInstanceOf(TimeSpec.AbsoluteRange.class, spec);
        assertNull(a.start().year());
        assertEquals(9, a.start().month());
        assertEquals(2026, a.end().year());
        assertEquals(TimeSpec.EndMode.INCLUSIVE_DATE, a.endMode());
    }

    @Test
    void rejectInvalid() {
        assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(json("{}")));
        assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(json(
                "{\"type\":\"calendar_period\",\"unit\":\"hour\",\"offset\":0,"
                        + "\"segment\":\"full\",\"extent\":\"full_period\"}")));
        // 时间格式非法
        assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(json(
                "{\"type\":\"relative_day_range\",\"start\":{\"day_offset\":0,\"time\":\"25:00:00\"},"
                        + "\"end\":{\"day_offset\":0,\"time\":\"26:00:00\"}}")));
        // inclusive_date 不允许 end.time
        assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(json(
                "{\"type\":\"absolute_range\",\"start\":{\"year\":2026,\"month\":9,\"day\":1,\"time\":null},"
                        + "\"end\":{\"year\":2026,\"month\":9,\"day\":3,\"time\":\"12:00:00\"},"
                        + "\"end_mode\":\"inclusive_date\"}")));
        // 非法时区
        assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(json(
                "{\"type\":\"rolling_window\",\"timezone\":\"Mars/Olympus\","
                        + "\"duration\":{\"value\":1,\"unit\":\"hour\"}}")));
        // duration.value 非正数
        assertThrows(IllegalArgumentException.class, () -> TimeSpecParser.parse(json(
                "{\"type\":\"rolling_window\",\"duration\":{\"value\":0,\"unit\":\"hour\"}}")));
    }
}
