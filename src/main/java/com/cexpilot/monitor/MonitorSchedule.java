package com.cexpilot.monitor;

import java.time.Instant;

public final class MonitorSchedule {
    private MonitorSchedule() {}
    public static Instant latestSlot(Instant now, int delaySeconds) {
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond()-delaySeconds,300)*300);
    }
}
