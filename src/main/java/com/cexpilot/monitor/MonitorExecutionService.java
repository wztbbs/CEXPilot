package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.cexpilot.monitor.MonitorModels.*;
import static com.cexpilot.monitor.MonitorExecution.State.*;

/** 持久化排期、领取、检查点和提交。锁顺序统一为 Rule → Execution；外部调用不在事务内。 */
@Service
public class MonitorExecutionService {
    private final JdbcTemplate jdbc;
    private final MonitorRepository rules;
    private final MonitorConfig config;
    private final MonitorJson json;
    private final Clock clock;
    private final MonitorTriggerService trigger;
    public MonitorExecutionService(JdbcTemplate jdbc,MonitorRepository rules,MonitorConfig config,MonitorJson json,Clock clock,MonitorTriggerService trigger) {
        this.jdbc=jdbc; this.rules=rules; this.config=config; this.json=json; this.clock=clock; this.trigger=trigger;
    }
    private static java.time.LocalDateTime utc(Instant time) { return MonitorRepository.utc(time); }
    private static Instant time(ResultSet rs,String column) throws SQLException {
        var value=rs.getObject(column,LocalDateTime.class); return value==null?null:value.toInstant(ZoneOffset.UTC);
    }
    private MonitorExecution map(ResultSet rs,int row) throws SQLException {
        return new MonitorExecution(rs.getString("execution_id"),rs.getString("rule_id"),rs.getInt("rule_version"),rs.getString("spec_hash"),
                time(rs,"scheduled_at"),time(rs,"deadline_at"),MonitorExecution.State.valueOf(rs.getString("status")),rs.getInt("attempt_count"),
                rs.getLong("lease_token"),time(rs,"lease_until"),time(rs,"attempt_deadline"),json.read(rs.getString("checkpoint_json")),
                rs.getString("result"),rs.getString("last_error"),time(rs,"started_at"),time(rs,"finished_at"));
    }
    public MonitorExecution find(String id) { return find(id,false); }
    private MonitorExecution find(String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM monitor_execution WHERE execution_id=?"+(lock?" FOR UPDATE":""),this::map,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<MonitorExecution> list(String ruleId) {
        return jdbc.query("SELECT * FROM monitor_execution WHERE rule_id=? ORDER BY scheduled_at DESC LIMIT 100",this::map,ruleId);
    }
    public int materializeDue() {
        int count=0; for(String id:rules.due(clock.instant())) count+=materialize(id); return count;
    }
    /** 先记下所有应执行周期，再原子推进游标；长停机按每任务每批100个周期渐进补账。 */
    public int materialize(String ruleId) {
        return rules.transaction(()->{
            var rule=rules.findAny(ruleId,true); Instant now=clock.instant();
            if(rule==null || !"ACTIVE".equals(rule.status()) || rule.nextCheckAt()==null || rule.nextCheckAt().isAfter(now)) return 0;
            var version=rules.version(ruleId,rule.activeVersion());
            Instant slot=MonitorSchedule.latestSlot(rule.nextCheckAt(),config.getPublicationDelaySeconds());
            int count=0;
            while(count<100 && !slot.plusSeconds(config.getPublicationDelaySeconds()).isAfter(now)) {
                Instant deadline=slot.plusSeconds(300);
                var completed=jdbc.queryForList("SELECT result FROM monitor_evaluation WHERE rule_id=? AND rule_version=? AND scheduled_at=?",String.class,ruleId,version.version(),utc(slot));
                boolean expired=!now.isBefore(deadline);
                String state=!completed.isEmpty()?"DONE":expired?"FAILED":"PENDING";
                jdbc.update("""
                        INSERT INTO monitor_execution (execution_id,rule_id,rule_version,spec_hash,scheduled_at,deadline_at,status,result,last_error,finished_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE execution_id=execution_id
                        """,UUID.randomUUID().toString(),ruleId,version.version(),version.specHash(),utc(slot),utc(deadline),state,
                        completed.isEmpty()?null:completed.get(0),completed.isEmpty() && expired?"EXECUTION_EXPIRED":null,
                        !"PENDING".equals(state)?utc(now):null,utc(now),utc(now));
                if(completed.isEmpty() && expired) breakHits(ruleId,slot);
                slot=slot.plusSeconds(300); count++;
            }
            jdbc.update("UPDATE monitor_rule SET next_check_at=?,updated_at=? WHERE rule_id=?",utc(slot.plusSeconds(config.getPublicationDelaySeconds())),utc(now),ruleId);
            return count;
        });
    }
    public List<String> ready() {
        var now=utc(clock.instant());
        return jdbc.queryForList("""
                SELECT e.execution_id FROM monitor_execution e JOIN monitor_rule r ON r.rule_id=e.rule_id
                WHERE e.status='PENDING' OR (e.status='RUNNING' AND (e.lease_until<=? OR e.attempt_deadline<=?))
                ORDER BY e.scheduled_at LIMIT 100
                """,String.class,now,now);
    }
    public MonitorWork claim(String id) {
        var initial=find(id); if(initial==null) return null;
        return rules.transaction(()->{
            var rule=rules.findAny(initial.ruleId(),true); var execution=find(id,true); Instant now=clock.instant();
            if(execution==null || terminal(execution)) return null;
            var committed=jdbc.queryForList("SELECT result FROM monitor_evaluation WHERE rule_id=? AND rule_version=? AND scheduled_at=?",String.class,
                    execution.ruleId(),execution.ruleVersion(),utc(execution.scheduledAt()));
            if(!committed.isEmpty()) {
                jdbc.update("UPDATE monitor_execution SET status='DONE',result=?,lease_until=NULL,attempt_deadline=NULL,finished_at=?,updated_at=? WHERE execution_id=?",
                        committed.get(0),utc(now),utc(now),execution.executionId());
                release(rule,execution.leaseToken(),now); return null;
            }
            if(!current(rule,execution)) { finish(execution,"CANCELLED","RULE_OR_VERSION_CHANGED",now); release(rule,execution.leaseToken(),now); return null; }
            if(!now.isBefore(execution.deadlineAt())) { finish(execution,"FAILED","EXECUTION_EXPIRED",now); release(rule,execution.leaseToken(),now); return null; }
            if(execution.status()==RUNNING && execution.leaseUntil().isAfter(now) && execution.attemptDeadline().isAfter(now)) return null;
            if(execution.status()==RUNNING) release(rule,execution.leaseToken(),now);
            if(execution.attemptCount()>=config.getMaxExecutionAttempts()) { finish(execution,"FAILED","ATTEMPTS_EXHAUSTED",now); return null; }
            rule=rules.findAny(rule.ruleId(),false);
            if(rule.leaseUntil()!=null && rule.leaseUntil().isAfter(now)) return null; // 同一Rule只运行一个周期
            var version=rules.version(rule.ruleId(),execution.ruleVersion());
            if(!execution.specHash().equals(version.specHash())) { finish(execution,"CANCELLED","DEFINITION_CHANGED",now); return null; }
            long token=rule.leaseToken()+1;
            Instant lease=now.plusSeconds(config.getLeaseSeconds());
            Instant attemptDeadline=now.plusSeconds(config.getWorkTimeoutSeconds());
            if(attemptDeadline.isAfter(execution.deadlineAt())) attemptDeadline=execution.deadlineAt();
            jdbc.update("UPDATE monitor_rule SET lease_token=?,lease_until=? WHERE rule_id=?",token,utc(lease),rule.ruleId());
            jdbc.update("""
                    UPDATE monitor_execution SET status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=?,attempt_deadline=?,
                    started_at=COALESCE(started_at,?),updated_at=? WHERE execution_id=?
                    """,token,utc(lease),utc(attemptDeadline),utc(now),utc(now),id);
            return new MonitorWork(UUID.randomUUID().toString(),find(id,false),new Claim(rules.findAny(rule.ruleId(),false),version,execution.scheduledAt()),now);
        });
    }
    public boolean checkpoint(MonitorWork work,JsonNode data) {
        return rules.transaction(()->{
            var rule=rules.findAny(work.rule().ruleId(),true); var execution=find(work.executionId(),true);
            if(!owns(rule,execution,work,true)) return false;
            jdbc.update("UPDATE monitor_execution SET checkpoint_json=?,updated_at=? WHERE execution_id=?",json.write(data),utc(clock.instant()),execution.executionId());
            return true;
        });
    }
    public boolean complete(MonitorWork work,Check check) {
        return rules.transaction(()->{
            var rule=rules.findAny(work.rule().ruleId(),true); var execution=find(work.executionId(),true);
            if(!owns(rule,execution,work,true)) return false;
            if(!rules.commit(work.claim(),check,work.startedAt(),trigger)) return false;
            jdbc.update("""
                    UPDATE monitor_execution SET status='DONE',result=?,last_error=?,lease_until=NULL,attempt_deadline=NULL,finished_at=?,updated_at=?
                    WHERE execution_id=?
                    """,check.result().name(),check.errorCode(),utc(clock.instant()),utc(clock.instant()),execution.executionId());
            return true;
        });
    }
    /** 返回true表示同一Execution可立即重试；失败回调只处理自己的token。 */
    public boolean fail(MonitorWork work,MonitorWorkFailure failure) {
        return rules.transaction(()->{
            var rule=rules.findAny(work.rule().ruleId(),true); var execution=find(work.executionId(),true); Instant now=clock.instant();
            if(!owns(rule,execution,work,false)) return false;
            boolean retry=failure.retryable() && execution.attemptCount()<config.getMaxExecutionAttempts() && now.isBefore(execution.deadlineAt());
            String error=failure.code()+": "+String.valueOf(failure.getMessage()); if(error.length()>1000) error=error.substring(0,1000);
            if(retry) jdbc.update("UPDATE monitor_execution SET status='PENDING',lease_until=NULL,attempt_deadline=NULL,last_error=?,updated_at=? WHERE execution_id=?",error,utc(now),execution.executionId());
            else finish(execution,"FAILED",error,now);
            release(rule,work.rule().leaseToken(),now); return retry;
        });
    }
    private boolean owns(Rule rule,MonitorExecution execution,MonitorWork work,boolean live) {
        if(execution==null || execution.status()!=RUNNING || execution.leaseToken()!=work.rule().leaseToken()
                || !current(rule,execution) || rule.leaseToken()!=work.rule().leaseToken()) return false;
        return !live || execution.leaseUntil().isAfter(clock.instant()) && execution.attemptDeadline().isAfter(clock.instant()) && execution.deadlineAt().isAfter(clock.instant());
    }
    private boolean current(Rule rule,MonitorExecution execution) { return rule!=null && "ACTIVE".equals(rule.status()) && Objects.equals(rule.activeVersion(),execution.ruleVersion()); }
    private boolean terminal(MonitorExecution execution) { return execution.status()==DONE || execution.status()==FAILED || execution.status()==CANCELLED; }
    private void finish(MonitorExecution execution,String status,String error,Instant now) {
        jdbc.update("UPDATE monitor_execution SET status=?,last_error=?,lease_until=NULL,attempt_deadline=NULL,finished_at=?,updated_at=? WHERE execution_id=?",status,error,utc(now),utc(now),execution.executionId());
        if("FAILED".equals(status)) breakHits(execution.ruleId(),execution.scheduledAt());
    }
    private void breakHits(String ruleId,Instant slot) {
        // 执行失败打断连续命中，但不解除 WAITING_RESET；补记历史过期周期不能清掉更新周期的命中。
        jdbc.update("UPDATE monitor_rule SET consecutive_hits=0,hit_evidence_json=NULL WHERE rule_id=? AND (last_evaluated_slot IS NULL OR last_evaluated_slot<=?)",ruleId,utc(slot));
    }
    private void release(Rule rule,long token,Instant now) {
        if(rule!=null) jdbc.update("UPDATE monitor_rule SET lease_until=NULL,lease_token=lease_token+1,updated_at=? WHERE rule_id=? AND lease_token=?",utc(now),rule.ruleId(),token);
    }
    public int cleanup() {
        return jdbc.update("DELETE FROM monitor_execution WHERE finished_at<? AND status IN ('DONE','FAILED','CANCELLED') LIMIT 1000",utc(clock.instant().minusSeconds(7*86400L)));
    }
}
