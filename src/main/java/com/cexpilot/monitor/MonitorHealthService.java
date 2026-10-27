package com.cexpilot.monitor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.cexpilot.monitor.MonitorHealthReport.*;

/** 请求触发的跨租户只读巡检。每项检查独立计数；SQL错误不会被解释成零违规。 */
@Service
public class MonitorHealthService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final MonitorConfig monitor;
    private final MonitorHealthConfig config;
    private final MonitorRuntimeStatus runtime;
    private final MonitorWorkManager manager;
    private final EmailNotificationSender sender;
    private final Clock clock;
    @Autowired
    public MonitorHealthService(JdbcTemplate jdbc,PlatformTransactionManager tx,MonitorConfig monitor,MonitorHealthConfig config,
            MonitorRuntimeStatus runtime,MonitorWorkManager manager,EmailNotificationSender sender,Clock clock) {
        // 独立JdbcTemplate，巡检超时配置不影响调度/提交。
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(config.getQueryTimeoutSeconds());
        this.snapshot=new TransactionTemplate(tx); snapshot.setReadOnly(true); snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(config.getTransactionTimeoutSeconds());
        this.monitor=monitor; this.config=config; this.runtime=runtime; this.manager=manager; this.sender=sender; this.clock=clock;
    }
    // 测试注入失败SQL，使用相同的事务/检查路径，不访问外部服务。
    MonitorHealthService(JdbcTemplate jdbc,TransactionTemplate tx,MonitorConfig monitor,MonitorHealthConfig config,
            MonitorRuntimeStatus runtime,MonitorWorkManager manager,EmailNotificationSender sender,Clock clock) {
        this.jdbc=jdbc; this.snapshot=tx; this.monitor=monitor; this.config=config; this.runtime=runtime; this.manager=manager; this.sender=sender; this.clock=clock;
    }
    private static final int CHECKS=7;
    private static final class Findings {
        long count,warnings,errorViolations; final int limit; final List<Violation> rows=new ArrayList<>();
        Findings(int limit) { this.limit=limit; }
        void add(Violation violation) {
            switch(violation.severity()) {
                case "WARN" -> warnings++;
                case "ERROR" -> errorViolations++;
                default -> throw new IllegalArgumentException("未知巡检级别");
            }
            count++; if(rows.size()<limit) rows.add(violation);
        }
        void merge(Findings other) {
            count+=other.count; warnings+=other.warnings; errorViolations+=other.errorViolations;
            for(var row:other.rows) if(rows.size()<limit) rows.add(row);
        }
    }
    private static final class ScopeTooLarge extends RuntimeException {}
    private record Scope(Instant now,Instant since,String ruleId,MonitorRuntimeStatus.Snapshot runtime) {}
    public MonitorHealthReport inspect(int sinceHours,String ruleId) {
        if(sinceHours<1 || sinceHours>24) throw new IllegalArgumentException("sinceHours 范围为1～24");
        if(ruleId!=null && !ruleId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("ruleId 格式无效");
        Instant now=clock.instant(); var local=runtime.snapshot();
        var scope=new Scope(now,now.minusSeconds(sinceHours*3600L),ruleId,local);
        var errors=new ArrayList<CheckError>(); var results=new ArrayList<CheckResult>(); var all=new Findings(config.getMaxViolations());
        var counts=new LinkedHashMap<String,Long>();
        try {
            snapshot.executeWithoutResult(ignored->{
                if(ruleId!=null && scalar("SELECT COUNT(*) FROM monitor_rule WHERE rule_id=?",ruleId)==0)
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"监控任务不存在");
                try { counts.putAll(counts(scope)); } catch(RuntimeException failure) { errors.add(new CheckError("COUNTS","CHECK_QUERY_FAILED")); }
                run("SCAN_HEALTH",()->scanHealth(scope),results,errors,all);
                run("SCHEDULE_COMPLETENESS",()->schedule(scope),results,errors,all);
                run("EXECUTION_STALLED",()->executionHealth(scope),results,errors,all);
                run("OWNERSHIP_CONSISTENCY",()->ownership(scope),results,errors,all);
                run("EVALUATION_CONSISTENCY",()->evaluations(scope),results,errors,all);
                run("EVENT_OUTBOX_CONSISTENCY",()->events(scope),results,errors,all);
                run("DELIVERY_HEALTH",()->delivery(scope),results,errors,all);
            });
        } catch(ResponseStatusException notFound) { throw notFound; }
        catch(RuntimeException failure) { errors.add(new CheckError("SNAPSHOT","CHECK_SNAPSHOT_FAILED")); }
        int ran=(int)results.stream().filter(r->!"ERROR".equals(r.status())).count();
        String status=!errors.isEmpty() || ran!=CHECKS?"INCOMPLETE":all.errorViolations>0?"UNHEALTHY":all.warnings>0?"DEGRADED":monitor.isEnabled()?"HEALTHY":"DISABLED";
        var info=new MonitorHealthReport.Runtime("INSTANCE",local.instanceId(),local.startedAt(),manager.activeWorks().size(),sender.isDryRun()?"DRY_RUN":"RESEND",sender.isAvailable(),
                local.schedule(),local.mail(),lag(local.schedule().lastSucceededAt(),local.startedAt(),now),lag(local.mail().lastSucceededAt(),local.startedAt(),now));
        return new MonitorHealthReport(now,sinceHours,ruleId,monitor.isEnabled(),status,info,counts,CHECKS,ran,List.copyOf(results),List.copyOf(errors),
                all.count,all.errorViolations,all.warnings,all.count>all.rows.size(),List.copyOf(all.rows));
    }
    private void run(String check,Supplier<Findings> action,List<CheckResult> results,List<CheckError> errors,Findings all) {
        try {
            var found=action.get(); all.merge(found); results.add(new CheckResult(check,found.count==0?"PASS":"VIOLATION",found.count));
        } catch(RuntimeException failure) {
            errors.add(new CheckError(check,failure instanceof ScopeTooLarge?"CHECK_SCOPE_TOO_LARGE":"CHECK_QUERY_FAILED"));
            results.add(new CheckResult(check,"ERROR",0));
        }
    }
    private Map<String,Long> counts(Scope s) {
        var counts=new LinkedHashMap<String,Long>();
        counts.put("activeRules",scalar("SELECT COUNT(*) FROM monitor_rule r WHERE r.status='ACTIVE'"+filter(s,"r"),args(s)));
        counts.put("executionsInWindow",scalar("SELECT COUNT(*) FROM monitor_execution e WHERE e.scheduled_at>=? AND e.scheduled_at<=?"+filter(s,"e"),args(s,utc(s.since),utc(s.now))));
        counts.put("eventsInWindow",scalar("SELECT COUNT(*) FROM monitor_event e WHERE e.triggered_at>=? AND e.triggered_at<=?"+filter(s,"e"),args(s,utc(s.since),utc(s.now))));
        String base=" FROM notification_delivery d JOIN monitor_event e ON e.event_id=d.event_id WHERE d.created_at>=? AND d.created_at<=?";
        counts.put("deliveriesInWindow",scalar("SELECT COUNT(*)"+base+filter(s,"e"),args(s,utc(s.since),utc(s.now))));
        counts.put("simulatedDeliveriesInWindow",scalar("SELECT COUNT(*)"+base+" AND d.provider_message_id LIKE 'dry-run:%'"+filter(s,"e"),args(s,utc(s.since),utc(s.now))));
        return counts;
    }
    private Findings scanHealth(Scope s) {
        var out=findings(); if(!monitor.isEnabled()) return out;
        scan(out,"SCHEDULE",s.runtime.schedule(),s.runtime.startedAt(),s.now);
        scan(out,"MAIL",s.runtime.mail(),s.runtime.startedAt(),s.now); return out;
    }
    private void scan(Findings out,String kind,MonitorRuntimeStatus.Scan scan,Instant start,Instant now) {
        if(scan.lastError()!=null) out.add(v(kind+"_SCAN_FAILED","ERROR",null,null,"最近一次扫描失败，类型："+scan.lastError()));
        else if(lag(scan.lastSucceededAt(),start,now)>config.getScanStaleSeconds())
            out.add(v(kind+"_SCAN_STALLED","ERROR",null,null,scan.lastSucceededAt()==null?"启动宽限期后仍无成功扫描":"成功扫描时间已超过容忍阈值"));
    }
    private Findings schedule(Scope s) {
        var out=findings(); if(!monitor.isEnabled()) return out;
        var rules=bounded("SELECT r.rule_id,r.active_version,r.next_check_at,v.approved_at FROM monitor_rule r LEFT JOIN monitor_rule_version v ON v.rule_id=r.rule_id AND v.version=r.active_version WHERE r.status='ACTIVE'"+filter(s,"r"),config.getMaxRules(),args(s));
        Instant last=MonitorSchedule.latestSlot(s.now.minusSeconds(config.getGraceSeconds()),monitor.getPublicationDelaySeconds());
        var actual=bounded("SELECT e.rule_id,e.rule_version,e.scheduled_at FROM monitor_execution e JOIN monitor_rule r ON r.rule_id=e.rule_id AND r.active_version=e.rule_version WHERE r.status='ACTIVE' AND e.scheduled_at>=? AND e.scheduled_at<=?"+filter(s,"e"),config.getMaxExecutions(),args(s,utc(s.since),utc(last)));
        var slots=new HashSet<String>();
        for(var row:actual) slots.add(key(str(row,"rule_id"),number(row,"rule_version"),instant(row,"scheduled_at")));
        for(var row:rules) {
            String id=str(row,"rule_id"); Instant cursor=instant(row,"next_check_at"),approved=instant(row,"approved_at");
            if(cursor==null) out.add(v("SCHEDULE_CURSOR_MISSING","ERROR",id,null,"已启用任务没有排期游标"));
            else if(cursor.isBefore(s.now.minusSeconds(config.getGraceSeconds()))) out.add(v("SCHEDULE_STALLED","ERROR",id,null,"排期游标落后 "+Duration.between(cursor,s.now).getSeconds()+" 秒"));
            if(approved==null || row.get("active_version")==null || approved.plusSeconds(config.getGraceSeconds()).isAfter(s.now)) continue;
            Instant first=MonitorSchedule.latestSlot(approved,monitor.getPublicationDelaySeconds());
            if(first.isBefore(s.since)) { first=MonitorSchedule.latestSlot(s.since,0); if(first.isBefore(s.since)) first=first.plusSeconds(300); }
            long missing=0; Instant earliest=null;
            for(Instant slot=first;!slot.isAfter(last);slot=slot.plusSeconds(300)) if(!slots.contains(key(id,number(row,"active_version"),slot))) { missing++; if(earliest==null) earliest=slot; }
            if(missing>0) out.add(v("SCHEDULE_GAP","ERROR",id,null,"缺少 "+missing+" 个应执行周期，最早截止点 "+earliest));
        }
        return out;
    }
    private Findings executionHealth(Scope s) {
        if(!monitor.isEnabled()) return findings();
        var rows=sqlFindings("SELECT e.rule_id,e.execution_id,e.status,e.attempt_count FROM monitor_execution e WHERE ((e.status='PENDING' AND e.scheduled_at<?) OR (e.status='RUNNING' AND (e.lease_until IS NULL OR e.attempt_deadline IS NULL OR e.lease_until<? OR e.attempt_deadline<? OR e.deadline_at<?)) OR (e.status='FAILED' AND e.finished_at>=? AND e.finished_at<=?))"+filter(s,"e"),
                args(s,utc(s.now.minusSeconds(config.getGraceSeconds()+monitor.getPublicationDelaySeconds())),utc(s.now.minusSeconds(config.getGraceSeconds())),utc(s.now.minusSeconds(config.getGraceSeconds())),utc(s.now.minusSeconds(config.getGraceSeconds())),utc(s.since),utc(s.now)),"status='FAILED'",row->{
                    String status=str(row,"status");
                    return v(status.equals("FAILED")?"EXECUTION_FAILED":"EXECUTION_STALLED",status.equals("FAILED")?"WARN":"ERROR",str(row,"rule_id"),str(row,"execution_id"),"状态 "+status+"，尝试 "+number(row,"attempt_count")+" 次");
                });
        return rows;
    }
    private Findings ownership(Scope s) {
        var out=findings();
        out.merge(sqlFindings("SELECT r.rule_id FROM monitor_rule r LEFT JOIN monitor_rule_version v ON v.rule_id=r.rule_id AND v.version=r.active_version WHERE r.status='ACTIVE' AND (r.active_version IS NULL OR v.rule_id IS NULL OR v.status<>'APPROVED' OR v.approved_at IS NULL)"+filter(s,"r"),args(s),row->v("RULE_VERSION_INVALID","ERROR",str(row,"rule_id"),null,"启用版本不存在或未批准")));
        out.merge(sqlFindings("SELECT e.rule_id,e.execution_id FROM monitor_execution e LEFT JOIN monitor_rule r ON r.rule_id=e.rule_id WHERE e.status='RUNNING' AND e.lease_until>? AND e.attempt_deadline>? AND e.deadline_at>? AND (r.rule_id IS NULL OR r.status<>'ACTIVE' OR r.active_version<>e.rule_version OR r.lease_token<>e.lease_token OR r.lease_until IS NULL OR r.lease_until<>e.lease_until)"+filter(s,"e"),args(s,utc(s.now),utc(s.now),utc(s.now)),row->v("EXECUTION_OWNERSHIP_INVALID","ERROR",str(row,"rule_id"),str(row,"execution_id"),"有效执行与任务占用或版本不一致")));
        out.merge(sqlFindings("SELECT r.rule_id FROM monitor_rule r WHERE r.status='ACTIVE' AND r.lease_until>? AND NOT EXISTS (SELECT 1 FROM monitor_execution e WHERE e.rule_id=r.rule_id AND e.status='RUNNING' AND e.lease_token=r.lease_token)"+filter(s,"r"),args(s,utc(s.now)),row->v("RULE_LEASE_ORPHAN","ERROR",str(row,"rule_id"),null,"任务有有效租约，但没有对应执行者")));
        out.merge(sqlFindings("SELECT e.rule_id FROM monitor_execution e WHERE e.status='RUNNING' AND e.lease_until>? AND e.attempt_deadline>? AND e.deadline_at>?"+filter(s,"e")+" GROUP BY e.rule_id HAVING COUNT(*)>1",args(s,utc(s.now),utc(s.now),utc(s.now)),row->v("MULTIPLE_EXECUTION_OWNERS","ERROR",str(row,"rule_id"),null,"同一任务存在多个有效执行者")));
        return out;
    }
    private Findings evaluations(Scope s) {
        var out=sqlFindings("SELECT e.rule_id,e.execution_id FROM monitor_execution e LEFT JOIN monitor_evaluation v ON v.rule_id=e.rule_id AND v.rule_version=e.rule_version AND v.scheduled_at=e.scheduled_at WHERE e.status='DONE' AND e.scheduled_at>=? AND e.scheduled_at<=? AND (v.evaluation_id IS NULL OR e.result IS NULL OR e.result<>v.result OR e.finished_at IS NULL)"+filter(s,"e"),args(s,utc(s.since),utc(s.now)),row->v("DONE_WITHOUT_VALID_EVALUATION","ERROR",str(row,"rule_id"),str(row,"execution_id"),"已完成执行缺少一致的检查结果"));
        out.merge(sqlFindings("SELECT e.rule_id,e.execution_id FROM monitor_evaluation v JOIN monitor_execution e ON e.rule_id=v.rule_id AND e.rule_version=v.rule_version AND e.scheduled_at=v.scheduled_at WHERE v.finished_at>=? AND v.finished_at<=? AND e.status<>'DONE'"+filter(s,"e"),args(s,utc(s.since),utc(s.now)),row->v("EVALUATION_WITH_UNFINISHED_EXECUTION","ERROR",str(row,"rule_id"),str(row,"execution_id"),"检查结果已提交，但执行未完成")));
        return out;
    }
    private Findings events(Scope s) {
        var out=sqlFindings("SELECT e.rule_id,e.event_id FROM monitor_event e LEFT JOIN monitor_evaluation v ON v.evaluation_id=e.evaluation_id WHERE e.triggered_at>=? AND e.triggered_at<=? AND (v.evaluation_id IS NULL OR v.rule_id<>e.rule_id OR v.rule_version<>e.rule_version OR v.result<>'MATCH')"+filter(s,"e"),args(s,utc(s.since),utc(s.now)),row->v("EVENT_EVALUATION_INVALID","ERROR",str(row,"rule_id"),null,"告警缺少一致的命中检查，事件 "+str(row,"event_id")));
        out.merge(sqlFindings("SELECT e.rule_id,e.event_id FROM monitor_event e WHERE e.triggered_at>=? AND e.triggered_at<=? AND NOT EXISTS (SELECT 1 FROM notification_delivery d WHERE d.event_id=e.event_id AND d.channel='EMAIL')"+filter(s,"e"),args(s,utc(s.since),utc(s.now)),row->v("EVENT_OUTBOX_MISSING","ERROR",str(row,"rule_id"),null,"告警缺少邮件Outbox，事件 "+str(row,"event_id"))));
        if(s.ruleId==null) out.merge(sqlFindings("SELECT d.delivery_id FROM notification_delivery d LEFT JOIN monitor_event e ON e.event_id=d.event_id WHERE e.event_id IS NULL AND (d.created_at>=? OR d.status IN ('PENDING','SENDING'))",new Object[]{utc(s.since)},row->v("DELIVERY_EVENT_MISSING","ERROR",null,null,"投递缺少告警，投递 "+str(row,"delivery_id"))));
        return out;
    }
    private Findings delivery(Scope s) {
        var out=findings();
        if(monitor.isEnabled() && !sender.isAvailable()) out.add(v("MAIL_NOT_CONFIGURED","ERROR",null,null,"监控已启用，但邮件发送器未配置"));
        String historical="(d.status IN ('FAILED','UNKNOWN') AND d.updated_at>=? AND d.updated_at<=?)";
        String live=monitor.isEnabled()?" OR (d.status='PENDING' AND d.next_retry_at<?) OR (d.status='SENDING' AND (d.lease_until IS NULL OR d.lease_until<?))":"";
        var params=new ArrayList<Object>(List.of(utc(s.since),utc(s.now)));
        if(monitor.isEnabled()) { params.add(utc(s.now.minusSeconds(config.getGraceSeconds()))); params.add(utc(s.now.minusSeconds(config.getGraceSeconds()))); }
        if(s.ruleId!=null) params.add(s.ruleId);
        out.merge(sqlFindings("SELECT e.rule_id,d.delivery_id,d.status FROM notification_delivery d JOIN monitor_event e ON e.event_id=d.event_id WHERE ("+historical+live+")"+filter(s,"e"),params.toArray(),"status='PENDING'",row->{
            String status=str(row,"status"); return v("DELIVERY_"+status,status.equals("PENDING")?"WARN":"ERROR",str(row,"rule_id"),null,"投递 "+str(row,"delivery_id")+"，状态 "+status);
        }));
        out.merge(sqlFindings("SELECT e.rule_id,d.delivery_id FROM notification_delivery d JOIN monitor_event e ON e.event_id=d.event_id WHERE d.status='SENT' AND d.updated_at>=? AND d.updated_at<=? AND (d.provider_message_id IS NULL OR d.sent_at IS NULL)"+filter(s,"e"),args(s,utc(s.since),utc(s.now)),row->v("DELIVERY_SENT_WITHOUT_RECEIPT","ERROR",str(row,"rule_id"),null,"已发送投递缺少接受记录，投递 "+str(row,"delivery_id"))));
        return out;
    }
    private Findings sqlFindings(String sql,Object[] params,java.util.function.Function<Map<String,Object>,Violation> convert) {
        return sqlFindings(sql,params,null,convert);
    }
    /** warningPredicate仅为内部固定SQL片段；严重性计数覆盖全量结果，不受返回LIMIT影响。 */
    private Findings sqlFindings(String sql,Object[] params,String warningPredicate,java.util.function.Function<Map<String,Object>,Violation> convert) {
        var out=findings(); out.count=scalar("SELECT COUNT(*) FROM ("+sql+") health_rows",params);
        out.warnings=warningPredicate==null?0:scalar("SELECT COUNT(*) FROM ("+sql+") health_rows WHERE "+warningPredicate,params);
        out.errorViolations=out.count-out.warnings;
        var values=new ArrayList<Object>(Arrays.asList(params)); values.add(config.getMaxViolations());
        for(var row:jdbc.queryForList(sql+" LIMIT ?",values.toArray())) out.rows.add(convert.apply(row));
        return out;
    }
    private List<Map<String,Object>> bounded(String sql,int max,Object...params) {
        var values=new ArrayList<Object>(Arrays.asList(params)); values.add(max+1);
        var rows=jdbc.queryForList(sql+" LIMIT ?",values.toArray()); if(rows.size()>max) throw new ScopeTooLarge(); return rows;
    }
    private long scalar(String sql,Object...params) { Long value=jdbc.queryForObject(sql,Long.class,params); return value==null?0:value; }
    private Findings findings() { return new Findings(config.getMaxViolations()); }
    private static Violation v(String code,String severity,String ruleId,String executionId,String detail) { return new Violation(code,severity,ruleId,executionId,detail); }
    private static String filter(Scope s,String alias) { return s.ruleId==null?"":" AND "+alias+".rule_id=?"; }
    private static Object[] args(Scope s,Object...prefix) { var values=new ArrayList<Object>(Arrays.asList(prefix)); if(s.ruleId!=null) values.add(s.ruleId); return values.toArray(); }
    private static LocalDateTime utc(Instant instant) { return MonitorRepository.utc(instant); }
    private static String str(Map<String,Object> row,String key) { var value=row.get(key); return value==null?null:value.toString(); }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static Instant instant(Map<String,Object> row,String key) {
        var value=row.get(key); if(value==null) return null;
        return value instanceof LocalDateTime local?local.toInstant(ZoneOffset.UTC):((java.sql.Timestamp)value).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
    private static String key(String ruleId,long version,Instant slot) { return ruleId+":"+version+":"+slot; }
    private static long lag(Instant success,Instant start,Instant now) { return Math.max(0,Duration.between(success==null?start:success,now).getSeconds()); }
}
