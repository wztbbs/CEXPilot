package com.cexpilot.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.core.task.TaskRejectedException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

/** 进程内执行管理：每Rule一个在途Work，有界线程池，超时撤销和同周期有界重试。 */
@Component
public class MonitorWorkManager {
    private static final Logger log=LoggerFactory.getLogger(MonitorWorkManager.class);
    private final MonitorExecutionService executions;
    private final MonitorWorker worker;
    private final MonitorExecutors pool;
    private final Clock clock;
    private final ConcurrentHashMap<String,Ticket> active=new ConcurrentHashMap<>();
    private static final class Ticket { volatile MonitorWork work; FutureTask<Void> future; boolean cancelled; }
    public MonitorWorkManager(MonitorExecutionService executions,MonitorWorker worker,MonitorExecutors pool,Clock clock) {
        this.executions=executions; this.worker=worker; this.pool=pool; this.clock=clock;
    }
    public void dispatch() {
        for(String id:executions.ready()) {
            var execution=executions.find(id); if(execution==null) continue;
            var ticket=new Ticket(); String ruleId=execution.ruleId();
            if(active.putIfAbsent(ruleId,ticket)!=null) continue;
            ticket.future=new FutureTask<>(()->{ execute(id,ruleId,ticket); return null; });
            try { pool.check(ticket.future); }
            catch(TaskRejectedException busy) { active.remove(ruleId,ticket); break; }
        }
    }
    /** 独立联调入口；与异步派发使用相同的执行/重试路径。 */
    void runNow(String id) {
        var execution=executions.find(id); if(execution==null) return;
        var ticket=new Ticket(); if(active.putIfAbsent(execution.ruleId(),ticket)==null) execute(id,execution.ruleId(),ticket);
    }
    private void execute(String id,String ruleId,Ticket ticket) {
        try {
            boolean retry;
            do {
                retry=false;
                MonitorWork work;
                // 与超时撤销串行，避免watchdog读取旧尝试后误取消刚接手的新尝试。
                synchronized(ticket) {
                    if(ticket.cancelled || Thread.currentThread().isInterrupted()) return;
                    work=executions.claim(id); ticket.work=work;
                }
                if(work==null) return;
                log.info("监控Work开始 workId={} executionId={} ruleId={} version={} slot={} attempt={}",work.workId(),id,ruleId,work.version().version(),work.slot(),work.execution().attemptCount());
                try {
                    var result=worker.run(work,data->{
                        if(!executions.checkpoint(work,data)) {
                            boolean timedOut=!clock.instant().isBefore(work.execution().leaseUntil());
                            throw new MonitorWorkFailure(timedOut?"WORK_TIMEOUT":"EXECUTION_FENCED","检查点执行权已失效",timedOut);
                        }
                    });
                    if(!executions.complete(work,result)) {
                        boolean timedOut=!clock.instant().isBefore(work.execution().leaseUntil());
                        retry=executions.fail(work,new MonitorWorkFailure(timedOut?"WORK_TIMEOUT":"EXECUTION_FENCED","本次结果未提交",timedOut));
                    }
                } catch(MonitorWorkFailure failure) { retry=executions.fail(work,failure); }
                catch(RuntimeException failure) {
                    log.warn("监控Work失败 workId={} executionId={} type={}",work.workId(),id,failure.getClass().getSimpleName());
                    retry=executions.fail(work,new MonitorWorkFailure("WORK_EXCEPTION",failure.getClass().getSimpleName(),true));
                }
            } while(retry);
        } catch(RuntimeException persistenceFailure) {
            // 数据库异常不能靠内存报告成功；保留持久化租约，后续扫描接管。
            log.error("监控执行管理失败 executionId={} type={}",id,persistenceFailure.getClass().getSimpleName());
        } finally { active.remove(ruleId,ticket); }
    }
    public void reapTimeouts() {
        active.forEach((ruleId,ticket)->{
            synchronized(ticket) {
                var work=ticket.work;
                if(work!=null && !clock.instant().isBefore(work.execution().leaseUntil())) {
                    executions.fail(work,new MonitorWorkFailure("WORK_TIMEOUT","执行超过本次尝试期限",true));
                    ticket.cancelled=true;
                    if(ticket.future!=null) ticket.future.cancel(true);
                    active.remove(ruleId,ticket);
                }
            }
        });
    }
    public List<MonitorWork> activeWorks() { return active.values().stream().map(t->t.work).filter(java.util.Objects::nonNull).toList(); }
}
