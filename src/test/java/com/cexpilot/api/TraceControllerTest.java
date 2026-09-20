package com.cexpilot.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceControllerTest {

    @Test
    void acceptValidWindows() {
        assertNull(TraceController.validateWindow(4, 0));
        assertNull(TraceController.validateWindow(12, 8));
        assertNull(TraceController.validateWindow(24, 0));
        assertNull(TraceController.validateWindow(0.5, 0));
    }

    @Test
    void rejectBeginNotAfterEnd() {
        assertNotNull(TraceController.validateWindow(0, 0));
        assertNotNull(TraceController.validateWindow(4, 4));
        assertNotNull(TraceController.validateWindow(2, 8));
    }

    @Test
    void rejectWindowLongerThan24Hours() {
        assertNotNull(TraceController.validateWindow(25, 0));
        assertNotNull(TraceController.validateWindow(30, 5));
    }

    @Test
    void rejectNegativeOrAbsurdHours() {
        assertNotNull(TraceController.validateWindow(4, -1));
        assertNotNull(TraceController.validateWindow(24 * 367, 24 * 366));
        assertNotNull(TraceController.validateWindow(Double.NaN, 0));
    }
}
