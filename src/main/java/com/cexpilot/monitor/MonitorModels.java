package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public final class MonitorModels {
    private MonitorModels() {}
    public record ConfirmRequest(int version, String specHash) {}
    public record Rule(String ruleId, String ownerId, String requestId, String status, Integer activeVersion,
                       Instant nextCheckAt, Instant lastEvaluatedSlot, int consecutiveHits, String triggerState,
                       JsonNode hitEvidence, Instant leaseUntil, long leaseToken, Instant createdAt, Instant updatedAt,
                       int pendingVersion,String sourceConversationId,String recipient) {
        public Rule(String ruleId,String ownerId,String requestId,String status,Integer activeVersion,Instant nextCheckAt,
                    Instant lastEvaluatedSlot,int consecutiveHits,String triggerState,JsonNode hitEvidence,Instant leaseUntil,
                    long leaseToken,Instant createdAt,Instant updatedAt) {
            this(ruleId,ownerId,requestId,status,activeVersion,nextCheckAt,lastEvaluatedSlot,consecutiveHits,triggerState,
                    hitEvidence,leaseUntil,leaseToken,createdAt,updatedAt,1,null,null);
        }
    }
    public record Version(String ruleId, int version, String userQuery, String specHash,
                          String status, Instant createdAt, Instant approvedAt, JsonNode definition) {
        public int requiredHits() { return definition.path("requiredHits").asInt(); }
    }
    public record Claim(Rule rule, Version version, Instant slot) {}
    public enum Result { MATCH, NO_MATCH, UNKNOWN }
    public record Check(Result result, JsonNode evidence, String errorCode) {}
    public record Transition(int hits, String state, JsonNode hitEvidence, boolean trigger) {}
    public record Delivery(String deliveryId, String eventId, String recipient, int attemptCount,
                           long leaseToken, JsonNode snapshot, Instant createdAt, boolean uncertain) {}
}
