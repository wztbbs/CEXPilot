package com.cexpilot.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MonitorHealthReport(Instant generatedAt,int sinceHours,String ruleId,boolean enabled,String status,
        Runtime runtime,Map<String,Long> counts,int checks,int ran,List<CheckResult> checkResults,
        List<CheckError> errors,long violationsTotal,long errorViolationsTotal,long warningsTotal,boolean truncated,List<Violation> violations) {
    public record Runtime(String scope,String instanceId,Instant startedAt,int activeWorks,String mailMode,boolean mailConfigured,
            MonitorRuntimeStatus.Scan schedule,MonitorRuntimeStatus.Scan mail,long scanLagSeconds,long mailScanLagSeconds) {}
    public record CheckResult(String check,String status,long violations) {}
    public record CheckError(String check,String code) {}
    public record Violation(String code,String severity,String ruleId,String executionId,String detail) {}
}
