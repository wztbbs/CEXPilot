package com.cexpilot.monitor;

import com.cexpilot.evidence.EvidenceSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.*;
import static com.cexpilot.monitor.MonitorModels.*;

/** 聊天候选 → 持久化草稿 → 明确接口确认。邮箱只用于通知，不用邮箱查找/认领其他浏览器任务。 */
@Service
public class MonitorConversationFlow {
    private final JdbcTemplate jdbc;
    private final MonitorRepository repository;
    private final MonitorDefinitionCompiler definitions;
    private final MonitorPlanRunner runner;
    private final MonitorConfig config;
    private final MonitorJson json;
    private final Clock clock;
    private final EmailNotificationSender mail;
    public MonitorConversationFlow(JdbcTemplate jdbc,MonitorRepository repository,MonitorDefinitionCompiler definitions,
            MonitorPlanRunner runner,MonitorConfig config,MonitorJson json,Clock clock,EmailNotificationSender mail) {
        this.jdbc=jdbc; this.repository=repository; this.definitions=definitions; this.runner=runner;
        this.config=config; this.json=json; this.clock=clock; this.mail=mail;
    }
    public static String owner(String visitor) {
        if(visitor==null || !visitor.matches("[A-Za-z0-9_-]{1,62}")) throw status(HttpStatus.UNAUTHORIZED,"请在创建监控的浏览器中访问");
        return "v:"+visitor;
    }
    public ObjectNode profile(String visitor) {
        owner(visitor);
        var addresses=jdbc.queryForList("SELECT email FROM monitor_subscriber WHERE visitor_id=?",String.class,visitor);
        var result=JsonNodeFactory.instance.objectNode().put("emailBound",!addresses.isEmpty());
        if(!addresses.isEmpty()) result.put("email",addresses.get(0));
        return result;
    }
    public ObjectNode bindEmail(String visitor,String address) {
        owner(visitor);
        if(address==null || address.length()>254 || address.contains("\r") || address.contains("\n")) throw new IllegalArgumentException("邮箱格式无效");
        address=address.trim();
        if(!MonitorEmailAddress.valid(address)) throw new IllegalArgumentException("请填写一个有效通知邮箱，不含展示名或地址列表");
        var now=MonitorRepository.utc(clock.instant());
        jdbc.update("""
                INSERT INTO monitor_subscriber (visitor_id,email,created_at,updated_at) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE email=VALUES(email),updated_at=VALUES(updated_at)
                """,visitor,address,now,now);
        return profile(visitor);
    }
    public ObjectNode saveDraft(String visitor,EvidenceSession session,String question,JsonNode candidate,String timezone) {
        String owner=owner(visitor);
        var definition=definitions.compile(candidate,timezone);
        String hash=MonitorJson.hash(canonical(definition).toString());
        return repository.transaction(()->{
            lockConversation(visitor,session.conversationId(),session.turnId());
            var repeated=jdbc.queryForList("SELECT rule_id,version FROM monitor_rule_version WHERE source_turn_id=?",session.turnId());
            if(!repeated.isEmpty()) {
                String id=(String)repeated.get(0).get("rule_id"); int version=((Number)repeated.get(0).get("version")).intValue();
                require(visitor,id,false);
                if(!hash.equals(repository.version(id,version).specHash())) throw conflict("本轮草稿已经保存为其他内容");
                return card(visitor,id,version);
            }
            var pending=jdbc.queryForList("""
                    SELECT rule_id FROM monitor_rule WHERE owner_id=? AND source_conversation_id=? AND status='PENDING_CONFIRMATION'
                    ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                    """,String.class,owner,session.conversationId());
            String id; int version;
            if(pending.isEmpty()) {
                id=UUID.randomUUID().toString(); version=1;
                jdbc.update("""
                        INSERT INTO monitor_rule (rule_id,owner_id,request_id,status,pending_version,source_conversation_id,created_at,updated_at)
                        VALUES (?,?,?,'PENDING_CONFIRMATION',1,?,?,?)
                        """,id,owner,session.turnId(),session.conversationId(),MonitorRepository.utc(clock.instant()),MonitorRepository.utc(clock.instant()));
            } else {
                id=pending.get(0); Rule rule=require(visitor,id,true); version=rule.pendingVersion()+1;
                jdbc.update("UPDATE monitor_rule_version SET status='SUPERSEDED' WHERE rule_id=? AND version=? AND status='PENDING'",id,rule.pendingVersion());
                jdbc.update("UPDATE monitor_rule SET pending_version=?,updated_at=? WHERE rule_id=?",version,MonitorRepository.utc(clock.instant()),id);
            }
            jdbc.update("""
                    INSERT INTO monitor_rule_version (rule_id,version,user_query,rule_spec_json,spec_hash,status,created_at,source_turn_id)
                    VALUES (?,?,?,?,?,'PENDING',?,?)
                    """,id,version,question,json.write(definition),hash,MonitorRepository.utc(clock.instant()),session.turnId());
            return card(visitor,id,version);
        });
    }
    public ObjectNode card(String visitor,String id,Integer version) {
        Rule rule=require(visitor,id,false);
        int number=version==null?(rule.activeVersion()==null?rule.pendingVersion():rule.activeVersion()):version;
        if(number<1) throw new IllegalArgumentException("无效版本");
        Version saved;
        try { saved=repository.version(id,number); }
        catch(org.springframework.dao.EmptyResultDataAccessException absent) { throw status(HttpStatus.NOT_FOUND,"规则版本不存在"); }
        if(saved.definition().path("schemaVersion").asInt()!=2) throw conflict("不支持的监控定义，请通过聊天重新创建");
        var definition=saved.definition();
        String current=!Objects.equals(number,rule.activeVersion()) && number!=rule.pendingVersion()?"SUPERSEDED":rule.status();
        if("SUPERSEDED".equals(saved.status())) current="SUPERSEDED";
        var card=JsonNodeFactory.instance.objectNode().put("ruleId",id).put("version",number).put("specHash",saved.specHash())
                .put("mailMode",mail.isDryRun()?"DRY_RUN":"RESEND").put("status",current).put("requiredHits",definition.path("requiredHits").asInt())
                .put("timezone",definition.path("timezone").asText()).put("relation","AND").put("checkIntervalSeconds",300);
        card.put("sourceConversationId",rule.sourceConversationId()).put("createdAt",rule.createdAt().toString())
                .put("updatedAt",rule.updatedAt().toString());
        if(rule.nextCheckAt()!=null) card.put("nextCheckAt",rule.nextCheckAt().toString());
        card.set("latestEvaluation",repository.latestEvaluation(id));
        card.set("candidate",definition.path("candidate")); card.set("rules",definition.path("rules")); card.set("profile",profile(visitor));
        String unavailable=unavailable(definition);
        card.put("executionAvailable",unavailable==null);
        if(unavailable!=null) card.put("unavailableReason",unavailable);
        card.put("canConfirm","PENDING_CONFIRMATION".equals(current) && profile(visitor).path("emailBound").asBoolean() && unavailable==null);
        if(rule.recipient()!=null) card.put("recipient",rule.recipient());
        return card;
    }
    public ObjectNode confirm(String visitor,String id,ConfirmRequest request,String recipient) {
        if(request==null || request.specHash()==null || request.version()<1) throw new IllegalArgumentException("需要确认版本与 specHash");
        if(recipient==null || recipient.isBlank()) throw new IllegalArgumentException("需要确认通知邮箱");
        return repository.transaction(()->{
            Rule initial=require(visitor,id,false);
            if("ACTIVE".equals(initial.status()) && Objects.equals(initial.activeVersion(),request.version())
                    && repository.version(id,request.version()).specHash().equals(request.specHash()) && recipient.equals(initial.recipient())) return card(visitor,id,null);
            lockConversation(visitor,initial.sourceConversationId(),null);
            Rule rule=require(visitor,id,true);
            if(!"PENDING_CONFIRMATION".equals(rule.status()) || rule.pendingVersion()!=request.version()) throw conflict("草稿已更新、取消或确认，请刷新当前规则");
            Version version=repository.version(id,request.version());
            if(!"PENDING".equals(version.status()) || !version.specHash().equals(request.specHash())) throw conflict("确认内容与当前版本不一致");
            var profile=profile(visitor);
            if(!profile.path("emailBound").asBoolean()) throw conflict("请先填写通知邮箱");
            if(!recipient.equals(profile.path("email").asText())) throw conflict("通知邮箱已变化，请核对当前地址后重新确认");
            String unavailable=unavailable(version.definition());
            if(unavailable!=null) throw status(HttpStatus.SERVICE_UNAVAILABLE,unavailable);
            jdbc.update("UPDATE monitor_rule SET recipient=? WHERE rule_id=?",profile.path("email").asText(),id);
            repository.activate(id,request.version(),clock.instant());
            return card(visitor,id,null);
        });
    }
    public void reject(String visitor,String id,ConfirmRequest request) {
        if(request==null || request.specHash()==null) throw new IllegalArgumentException("需要草稿版本与 specHash");
        repository.transaction(()->{
            Rule initial=require(visitor,id,false); lockConversation(visitor,initial.sourceConversationId(),null);
            Rule rule=require(visitor,id,true);
            if(!"PENDING_CONFIRMATION".equals(rule.status()) || rule.pendingVersion()!=request.version()) throw conflict("草稿已变化，不能取消其他版本或已启用任务");
            Version version=repository.version(id,request.version());
            if(!version.specHash().equals(request.specHash())) throw conflict("草稿内容不一致");
            repository.stop(id,clock.instant());
            jdbc.update("UPDATE monitor_rule_version SET status='REJECTED' WHERE rule_id=? AND version=?",id,request.version());
            return null;
        });
    }
    public List<JsonNode> executions(String visitor,String id) { require(visitor,id,false); return repository.executions(id); }
    public List<com.fasterxml.jackson.databind.JsonNode> events(String visitor,String id) { require(visitor,id,false); return repository.events(id); }
    public List<ObjectNode> list(String visitor) {
        return repository.list(owner(visitor)).stream().filter(r->r.sourceConversationId()!=null).map(r->card(visitor,r.ruleId(),null)).toList();
    }
    public void stop(String visitor,String id,ConfirmRequest request) {
        if(request==null || request.specHash()==null) throw new IllegalArgumentException("需要任务版本与 specHash");
        repository.transaction(()->{
            Rule rule=require(visitor,id,true);
            if(!"ACTIVE".equals(rule.status()) || !Objects.equals(rule.activeVersion(),request.version())
                    || !repository.version(id,request.version()).specHash().equals(request.specHash())) throw conflict("任务版本或状态已变化，请刷新");
            repository.stop(id,clock.instant()); return null;
        });
    }
    public JsonNode enrichState(String visitor,JsonNode original) {
        if(visitor==null || original==null || !original.isObject()) return original;
        var result=((ObjectNode)original).deepCopy(); var previous=result.at("/monitor_context/confirmation");
        if(previous.hasNonNull("ruleId")) {
            try { ((ObjectNode)result.path("monitor_context")).set("confirmation",card(visitor,previous.path("ruleId").asText(),previous.path("version").asInt())); }
            catch(ResponseStatusException stale) { ((ObjectNode)result.path("monitor_context")).remove("confirmation"); }
        }
        return result;
    }
    private Rule require(String visitor,String id,boolean lock) {
        var rule=repository.find(owner(visitor),id,lock);
        if(rule==null) throw status(HttpStatus.NOT_FOUND,"当前浏览器没有这条监控");
        return rule;
    }
    private String unavailable(JsonNode definition) {
        if(definition==null || definition.path("schemaVersion").asInt()!=2) return "不支持的监控定义，请重新创建";
        if(!config.isEnabled()) return "主动监控服务尚未启用";
        if(!mail.isAvailable()) return "Resend 邮件服务尚未配置，暂不能启用监控";
        try { runner.validateRunnable(definition); return null; }
        catch(IllegalArgumentException invalid) { return invalid.getMessage(); }
    }
    /** 与会话收尾统一锁顺序：conversation_state → conversation → monitor_rule。 */
    private void lockConversation(String visitor,String conversationId,String runningTurn) {
        if(conversationId==null) throw conflict("任务缺少来源会话");
        var valid=jdbc.query("SELECT running_turn_id,lease_until>NOW(6) live FROM conversation_state WHERE conversation_id=? FOR UPDATE",(rs,n)->{
            String active=rs.getString("running_turn_id"); boolean live=rs.getBoolean("live");
            return runningTurn==null?active==null || !live:runningTurn.equals(active) && live;
        },conversationId);
        if(valid.isEmpty() || !valid.get(0)) throw conflict(runningTurn==null?"会话正在生成新结果，请稍后再确认":"本轮执行权已失效，未保存监控草稿");
        var owned=jdbc.queryForList("SELECT visitor_id FROM conversation WHERE conversation_id=? FOR UPDATE",String.class,conversationId);
        if(owned.isEmpty() || !Objects.equals(visitor,owned.get(0))) throw status(HttpStatus.NOT_FOUND,"当前浏览器没有这段会话");
    }
    private static JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            var sorted=JsonNodeFactory.instance.objectNode(); var keys=new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key->sorted.set(key,canonical(node.get(key)))); return sorted;
        }
        if(node.isArray()) { var array=JsonNodeFactory.instance.arrayNode(); node.forEach(value->array.add(canonical(value))); return array; }
        if(node.isNumber()) return JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros());
        return node.deepCopy();
    }
    private static ResponseStatusException conflict(String message) { return status(HttpStatus.CONFLICT,message); }
    private static ResponseStatusException status(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
