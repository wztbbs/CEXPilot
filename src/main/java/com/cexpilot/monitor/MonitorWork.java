package com.cexpilot.monitor;

import java.time.Instant;
import static com.cexpilot.monitor.MonitorModels.*;

/** 不持久化；每次领取生成新的 workId，执行身份、版本和截止点由 Execution 固定。 */
public record MonitorWork(String workId,MonitorExecution execution,Claim claim,Instant startedAt) {
    public String executionId() { return execution.executionId(); }
    public Rule rule() { return claim.rule(); }
    public Version version() { return claim.version(); }
    public Instant slot() { return claim.slot(); }
}
