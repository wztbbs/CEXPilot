package com.cexpilot.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimesTest {

    @Test
    void rendersUtc8ReadableTime() {
        // 1789920000000 = 2026-09-21 00:00:00 UTC+8
        assertEquals("2026-09-21 00:00:00", Times.readable(1789920000000L));
        // 1789844400000 = 2026-09-20 03:00:00 UTC+8（模型曾错算成 2024-07-20）
        assertEquals("2026-09-20 03:00:00", Times.readable(1789844400000L));
    }
}
