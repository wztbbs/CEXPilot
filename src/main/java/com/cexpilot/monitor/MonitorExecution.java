package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** 一个已持久化的应执行周期；Work 是完成它的一次内存尝试。 */
public record MonitorExecution(String executionId,String ruleId,int ruleVersion,String specHash,Instant scheduledAt,
        Instant deadlineAt,State status,int attemptCount,long leaseToken,Instant leaseUntil,
        JsonNode checkpoint,String result,String lastError,Instant startedAt,Instant finishedAt) {
    public enum State { PENDING,RUNNING,DONE,FAILED,CANCELLED }
}
