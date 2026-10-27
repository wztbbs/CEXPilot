package com.cexpilot.monitor;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 本实例扫描的开始、成功和失败快照；不是Work心跳，也不持久化。 */
@Component
public class MonitorRuntimeStatus {
    public record Scan(Instant lastStartedAt,Instant lastSucceededAt,Instant lastFailedAt,String lastError,boolean running) {}
    public record Snapshot(String instanceId,Instant startedAt,Scan schedule,Scan mail) {}
    private final Clock clock;
    private final String instanceId=UUID.randomUUID().toString();
    private final Instant startedAt;
    private final AtomicReference<Scan> schedule=new AtomicReference<>(new Scan(null,null,null,null,false));
    private final AtomicReference<Scan> mail=new AtomicReference<>(new Scan(null,null,null,null,false));
    public MonitorRuntimeStatus(Clock clock) { this.clock=clock; this.startedAt=clock.instant(); }
    public void scheduleStarted() { start(schedule); }
    public void scheduleSucceeded() { success(schedule); }
    public void scheduleFailed(Throwable error) { failure(schedule,error); }
    public void mailStarted() { start(mail); }
    public void mailSucceeded() { success(mail); }
    public void mailFailed(Throwable error) { failure(mail,error); }
    private void start(AtomicReference<Scan> target) { target.updateAndGet(s->new Scan(clock.instant(),s.lastSucceededAt(),s.lastFailedAt(),s.lastError(),true)); }
    private void success(AtomicReference<Scan> target) { target.updateAndGet(s->new Scan(s.lastStartedAt(),clock.instant(),s.lastFailedAt(),null,false)); }
    private void failure(AtomicReference<Scan> target,Throwable error) {
        target.updateAndGet(s->new Scan(s.lastStartedAt(),s.lastSucceededAt(),clock.instant(),error.getClass().getSimpleName(),false));
    }
    public Snapshot snapshot() { return new Snapshot(instanceId,startedAt,schedule.get(),mail.get()); }
}
