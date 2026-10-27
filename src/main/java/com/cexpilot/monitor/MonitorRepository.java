package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static com.cexpilot.monitor.MonitorModels.*;

/** 所有业务状态变更通过短事务提交；外部行情/邮件调用不得放在这些事务内。 */
@Repository
public class MonitorRepository {
    static final String UNCERTAIN_PREFIX="RESEND_UNCERTAIN: ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MonitorJson json;
    private final MonitorConfig config;
    private final Clock clock;
    public MonitorRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, MonitorJson json,
                             MonitorConfig config, Clock clock) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.json = json;
        this.config = config; this.clock = clock;
    }
    public <T> T transaction(Supplier<T> work) { return tx.execute(ignored -> work.get()); }
    public static LocalDateTime utc(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        var value = rs.getObject(field, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private Rule rule(ResultSet rs, int row) throws SQLException {
        return new Rule(rs.getString("rule_id"), rs.getString("owner_id"), rs.getString("request_id"),
                rs.getString("status"), (Integer) rs.getObject("active_version"), instant(rs,"next_check_at"),
                instant(rs,"last_evaluated_slot"), rs.getInt("consecutive_hits"), rs.getString("trigger_state"),
                json.read(rs.getString("hit_evidence_json")), instant(rs,"lease_until"), rs.getLong("lease_token"),
                instant(rs,"created_at"), instant(rs,"updated_at"),rs.getInt("pending_version"),rs.getString("source_conversation_id"),rs.getString("recipient"));
    }
    public Rule find(String owner, String id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM monitor_rule WHERE owner_id=? AND rule_id=?" + (lock ? " FOR UPDATE" : ""), this::rule, owner, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<Rule> list(String owner) {
        return jdbc.query("SELECT * FROM monitor_rule WHERE owner_id=? ORDER BY created_at DESC LIMIT 100", this::rule, owner);
    }
    public Version version(String id, int version) {
        return jdbc.queryForObject("SELECT * FROM monitor_rule_version WHERE rule_id=? AND version=?", (rs,n) -> {
            return new Version(id, version, rs.getString("user_query"),rs.getString("spec_hash"),
                    rs.getString("status"), instant(rs,"created_at"), instant(rs,"approved_at"),json.read(rs.getString("rule_spec_json")));
        }, id, version);
    }
    public void activate(String id, int version, Instant now) {
        jdbc.update("UPDATE monitor_rule_version SET status='APPROVED',approved_at=? WHERE rule_id=? AND version=?", utc(now), id, version);
        jdbc.update("UPDATE monitor_rule SET status='ACTIVE',active_version=?,next_check_at=?,lease_token=lease_token+1,updated_at=? WHERE rule_id=?",
                version, utc(now), utc(now), id);
    }
    public void stop(String id, Instant now) {
        jdbc.update("UPDATE monitor_rule SET status='DELETED',next_check_at=NULL,lease_until=NULL,lease_token=lease_token+1,consecutive_hits=0,hit_evidence_json=NULL,updated_at=? WHERE rule_id=?",
                utc(now), id);
        jdbc.update("UPDATE monitor_execution SET status='CANCELLED',lease_until=NULL,attempt_deadline=NULL,finished_at=?,updated_at=?,last_error='RULE_STOPPED' WHERE rule_id=? AND status IN ('PENDING','RUNNING')",utc(now),utc(now),id);
    }
    public JsonNode latestEvaluation(String id) {
        var rows = jdbc.query("SELECT * FROM monitor_evaluation WHERE rule_id=? ORDER BY scheduled_at DESC LIMIT 1", (rs,n) -> {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("evaluationId",rs.getString("evaluation_id")).put("result",rs.getString("result"))
                    .put("scheduledAt",instant(rs,"scheduled_at").toString()).put("errorCode",rs.getString("error_code"));
            node.set("evidence",json.read(rs.getString("evidence_json"))); return (JsonNode)node;
        }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<JsonNode> executions(String id) {
        return jdbc.query("SELECT * FROM monitor_execution WHERE rule_id=? ORDER BY scheduled_at DESC LIMIT 100",(rs,n)->{
            var node=JsonNodeFactory.instance.objectNode().put("executionId",rs.getString("execution_id")).put("ruleVersion",rs.getInt("rule_version"))
                    .put("status",rs.getString("status")).put("attemptCount",rs.getInt("attempt_count")).put("scheduledAt",instant(rs,"scheduled_at").toString())
                    .put("deadlineAt",instant(rs,"deadline_at").toString()).put("result",rs.getString("result")).put("lastError",rs.getString("last_error"));
            if(instant(rs,"started_at")!=null) node.put("startedAt",instant(rs,"started_at").toString());
            if(instant(rs,"finished_at")!=null) node.put("finishedAt",instant(rs,"finished_at").toString());
            var checkpoint=json.read(rs.getString("checkpoint_json"));
            node.put("checkpointNodeCount",checkpoint.path("nodes").size()); return (JsonNode)node;
        },id);
    }
    public List<JsonNode> events(String id) {
        return jdbc.query("""
                SELECT e.*,d.status delivery_status,d.attempt_count,d.last_error,d.provider_message_id FROM monitor_event e
                LEFT JOIN notification_delivery d ON d.event_id=e.event_id
                WHERE e.rule_id=? ORDER BY e.triggered_at DESC LIMIT 100
                """, (rs,n) -> {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("eventId",rs.getString("event_id")).put("triggeredAt",instant(rs,"triggered_at").toString())
                    .put("deliveryStatus",rs.getString("delivery_status")).put("attemptCount",rs.getInt("attempt_count"))
                    .put("lastError",rs.getString("last_error"));
            String messageId=rs.getString("provider_message_id");
            node.put("providerMessageId",messageId).put("simulated",messageId!=null && messageId.startsWith("dry-run:"));
            node.set("evidence",json.read(rs.getString("evidence_snapshot"))); return node;
        }, id);
    }
    public List<String> due(Instant now) {
        return jdbc.queryForList("""
                SELECT rule_id FROM monitor_rule WHERE status='ACTIVE' AND next_check_at<=?
                ORDER BY next_check_at LIMIT 20
                """, String.class, utc(now));
    }
    boolean commit(Claim claim, Check check, Instant started, MonitorTriggerService trigger) {
        return transaction(() -> {
            Rule r = find(claim.rule().ownerId(), claim.rule().ruleId(), true);
            Instant now = clock.instant();
            if (r == null || !"ACTIVE".equals(r.status()) || !r.activeVersion().equals(claim.version().version())
                    || r.leaseToken()!=claim.rule().leaseToken() || r.leaseUntil()==null || !r.leaseUntil().isAfter(now)
                    || (r.lastEvaluatedSlot()!=null && !claim.slot().isAfter(r.lastEvaluatedSlot()))) return false;
            // 唯一检查记录先写入，同一事务里才能推进计数和创建业务事件。
            String evaluationId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO monitor_evaluation (evaluation_id,rule_id,rule_version,scheduled_at,data_cutoff_at,started_at,finished_at,result,evidence_json,error_code)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, evaluationId,r.ruleId(),claim.version().version(),utc(claim.slot()),utc(claim.slot()),utc(started),utc(now),
                    check.result().name(),json.write(check.evidence()),check.errorCode());
            Transition next = trigger.apply(r, claim.version().requiredHits(), claim.slot(), check);
            if (next.trigger()) {
                var snapshot = JsonNodeFactory.instance.objectNode();
                snapshot.put("schemaVersion",2).put("ruleId",r.ruleId()).put("ruleVersion",claim.version().version())
                        .put("specHash",claim.version().specHash()).put("metric","monitor.plan").put("unit","mixed")
                        .put("evaluationId",evaluationId).put("triggeredAt",now.toString());
                snapshot.set("definition",claim.version().definition()); snapshot.set("rules",claim.version().definition().path("rules"));
                snapshot.set("checks",next.hitEvidence());
                String eventId = UUID.randomUUID().toString();
                String recipient=r.recipient();
                snapshot.set("resend_request",MonitorEmailMessage.freeze(snapshot,eventId,config.getMailFrom(),recipient));
                jdbc.update("INSERT INTO monitor_event (event_id,rule_id,rule_version,evaluation_id,event_key,triggered_at,evidence_snapshot) VALUES (?,?,?,?,?,?,?)",
                        eventId,r.ruleId(),claim.version().version(),evaluationId,
                        MonitorJson.hash(r.ruleId()+":"+claim.version().version()+":"+claim.slot()),utc(now),json.write(snapshot));
                jdbc.update("""
                        INSERT INTO notification_delivery (delivery_id,event_id,channel,recipient,status,next_retry_at,created_at,updated_at)
                        VALUES (?,?,'EMAIL',?,'PENDING',?,?,?)
                        """, UUID.randomUUID().toString(),eventId,recipient,utc(now),utc(now),utc(now));
            }
            jdbc.update("""
                    UPDATE monitor_rule SET last_evaluated_slot=?,consecutive_hits=?,trigger_state=?,
                    hit_evidence_json=?,lease_until=NULL,updated_at=? WHERE rule_id=?
                    """,utc(claim.slot()),next.trigger()?0:next.hits(),next.state(),json.write(next.trigger()?List.of():next.hitEvidence()),utc(now),r.ruleId());
            return true;
        });
    }
    public List<String> dueDeliveries() {
        Instant now = clock.instant();
        return jdbc.queryForList("""
                SELECT d.delivery_id FROM notification_delivery d JOIN monitor_event e ON e.event_id=d.event_id
                JOIN monitor_rule r ON r.rule_id=e.rule_id WHERE
                ((d.status='PENDING' AND d.next_retry_at<=?) OR (d.status='SENDING' AND d.lease_until<=?))
                ORDER BY d.next_retry_at LIMIT 20
                """,String.class,utc(now),utc(now));
    }
    public Delivery claimDelivery(String id) {
        return transaction(() -> {
            var rows = jdbc.queryForList("SELECT * FROM notification_delivery WHERE delivery_id=? FOR UPDATE",id);
            if (rows.isEmpty()) return null;
            var row = rows.get(0);
            String status = (String)row.get("status");
            Instant now = clock.instant();
            Instant created=jdbc.queryForObject("SELECT created_at FROM notification_delivery WHERE delivery_id=?",(rs,n)->instant(rs,"created_at"),id);
            int attempts=((Number)row.get("attempt_count")).intValue();
            if(!"PENDING".equals(status) && !"SENDING".equals(status)) return null;
            boolean expired=!now.isBefore(created.plusSeconds(23*3600));
            if("SENDING".equals(status)) {
                // 新 Resend 通知在安全窗口内凭同一幂等键恢复；旧发送器的在途记录不自动跨渠道重投。
                String eventId=(String)row.get("event_id");
                var saved=jdbc.queryForObject("SELECT evidence_snapshot FROM monitor_event WHERE event_id=?",(rs,n)->json.read(rs.getString(1)),eventId);
                boolean recoverable=!expired && attempts<config.getMaxDeliveryAttempts() && saved.has("resend_request");
                jdbc.update("""
                        UPDATE notification_delivery SET status=?,lease_until=NULL,lease_token=lease_token+1,
                        last_error=?,updated_at=? WHERE delivery_id=? AND status='SENDING' AND lease_until<=?
                        """,recoverable?"PENDING":"UNKNOWN",UNCERTAIN_PREFIX+"发送租约过期，投递结果待核实",utc(now),id,utc(now));
                return null;
            }
            if(expired) {
                jdbc.update("UPDATE notification_delivery SET status=?,last_error=?,updated_at=? WHERE delivery_id=?",
                        attempts==0?"FAILED":"UNKNOWN","超过23小时安全投递窗口，未再次发送",utc(now),id);
                return null;
            }
            if(attempts>=config.getMaxDeliveryAttempts()) {
                jdbc.update("UPDATE notification_delivery SET status='UNKNOWN',last_error=?,updated_at=? WHERE delivery_id=?",
                        "投递次数已达到上限，未再次发送",utc(now),id); return null;
            }
            // 兼容升级前尚未开始发送的 Outbox；冻结一次，不改写历史行情证据。
            String eventId=(String)row.get("event_id");
            var saved=jdbc.queryForObject("SELECT evidence_snapshot FROM monitor_event WHERE event_id=?",(rs,n)->json.read(rs.getString(1)),eventId);
            if(!saved.has("resend_request")) {
                if(attempts>0) {
                    jdbc.update("UPDATE notification_delivery SET status='UNKNOWN',last_error=?,updated_at=? WHERE delivery_id=?",
                            "旧渠道已尝试发送，需人工核对后处理",utc(now),id); return null;
                }
                var frozen=(com.fasterxml.jackson.databind.node.ObjectNode)saved.deepCopy();
                frozen.set("resend_request",MonitorEmailMessage.freeze(saved,eventId,config.getMailFrom(),(String)row.get("recipient")));
                jdbc.update("UPDATE monitor_event SET evidence_snapshot=? WHERE event_id=?",json.write(frozen),eventId);
            }
            int updated = jdbc.update("""
                    UPDATE notification_delivery SET status='SENDING',attempt_count=attempt_count+1,
                    lease_token=lease_token+1,lease_until=?,updated_at=? WHERE delivery_id=? AND status='PENDING' AND next_retry_at<=?
                    """,utc(now.plusSeconds(config.getLeaseSeconds())),utc(now),id,utc(now));
            if (updated==0) return null;
            return jdbc.queryForObject("""
                    SELECT d.*,e.evidence_snapshot FROM notification_delivery d JOIN monitor_event e ON e.event_id=d.event_id
                    WHERE d.delivery_id=?
                    """,(rs,n)->new Delivery(id,rs.getString("event_id"),rs.getString("recipient"),rs.getInt("attempt_count"),
                    rs.getLong("lease_token"),json.read(rs.getString("evidence_snapshot")),instant(rs,"created_at"),
                    rs.getString("last_error")!=null && rs.getString("last_error").startsWith(UNCERTAIN_PREFIX)),id);
        });
    }
    public void finishDelivery(Delivery delivery, String status, String messageId, String error) {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE notification_delivery SET status=?,provider_message_id=?,last_error=?,sent_at=?,next_retry_at=?,
                lease_until=NULL,updated_at=? WHERE delivery_id=? AND status='SENDING' AND lease_token=? AND lease_until>?
                """,status,messageId,error,"SENT".equals(status)?utc(now):null,
                utc(now.plusSeconds(60L*(1L<<Math.min(delivery.attemptCount(),8)))),utc(now),delivery.deliveryId(),delivery.leaseToken(),utc(now));
    }
    public int cleanupEvaluations() {
        return jdbc.update("DELETE FROM monitor_evaluation WHERE finished_at<? LIMIT 1000",utc(clock.instant().minusSeconds(7*86400L)));
    }
    Rule findAny(String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM monitor_rule WHERE rule_id=?"+(lock?" FOR UPDATE":""),this::rule,id);
        return rows.isEmpty()?null:rows.get(0);
    }
}
