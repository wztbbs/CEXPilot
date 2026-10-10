package com.cexpilot.time;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.format.DateTimeFormatter;

/** TimeSpec 的协议表示，供 trace 使用；不依赖 Jackson 对 Java 时间类型及枚举的默认序列化。 */
public final class TimeSpecJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TimeSpecJson() {}

    public static ObjectNode write(TimeSpec spec) {
        ObjectNode out = JSON.createObjectNode().put("type", spec.type().code());
        if (spec.timezone() != null) out.put("timezone", spec.timezone().getId());
        if (spec instanceof TimeSpec.CalendarPeriod p) {
            out.put("unit", p.unit().code()).put("offset", p.offset())
                    .put("segment", p.segment().code()).put("extent", p.extent().code());
        } else if (spec instanceof TimeSpec.RollingWindow p) {
            out.putObject("duration")
                    .put("value", p.duration().value()).put("unit", p.duration().unit().code());
        } else if (spec instanceof TimeSpec.RelativeDayRange p) {
            out.set("start", dayPoint(p.start()));
            out.set("end", dayPoint(p.end()));
        } else if (spec instanceof TimeSpec.AbsoluteRange p) {
            out.set("start", absolutePoint(p.start()));
            out.set("end", absolutePoint(p.end()));
            out.put("end_mode", p.endMode().code());
        } else {
            throw new IllegalArgumentException("未知时间类型: " + spec.getClass());
        }
        return out;
    }

    private static ObjectNode dayPoint(TimeSpec.DayTimePoint point) {
        return JSON.createObjectNode().put("day_offset", point.dayOffset())
                .put("time", point.time().format(DateTimeFormatter.ISO_LOCAL_TIME));
    }

    private static ObjectNode absolutePoint(TimeSpec.AbsolutePoint point) {
        ObjectNode out = JSON.createObjectNode().put("month", point.month()).put("day", point.day());
        if (point.year() != null) out.put("year", point.year());
        if (point.time() != null) out.put("time", point.time().format(DateTimeFormatter.ISO_LOCAL_TIME));
        return out;
    }
}
