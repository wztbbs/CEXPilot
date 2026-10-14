package com.cexpilot.metric;

/** 字段本轮来源、原始来源性质和继承基准；标识均由服务端填充，模型只能提供用户原文。 */
public record ConditionSource(ConditionOrigin origin, ConditionOrigin requirementOrigin,
        String sourceTurnId, String sourceFrameId, Long sourceVersion, String userText,
        String requirementTurnId, String requirementFrameId, Long requirementVersion) {
    public ConditionSource(ConditionOrigin origin, ConditionOrigin requirementOrigin,
            String sourceTurnId, String sourceFrameId, Long sourceVersion, String userText) {
        this(origin, requirementOrigin, sourceTurnId, sourceFrameId, sourceVersion, userText, null, null, null);
    }

    public static ConditionSource of(ConditionOrigin origin) {
        return new ConditionSource(origin, origin, null, null, null, null);
    }

    public ConditionSource inherited(String turnId, String frameId, long version) {
        return new ConditionSource(ConditionOrigin.INHERITED, requirementOrigin,
                turnId, frameId, version, userText,
                requirementTurnId != null ? requirementTurnId : sourceTurnId != null ? sourceTurnId : turnId,
                requirementFrameId != null ? requirementFrameId : sourceFrameId != null ? sourceFrameId : frameId,
                requirementVersion != null ? requirementVersion : sourceVersion != null ? sourceVersion : version);
    }

    public ConditionSource identify(String turnId, String frameId, long version) {
        return sourceTurnId != null ? this : new ConditionSource(origin, requirementOrigin,
                turnId, frameId, version, userText,
                requirementTurnId != null ? requirementTurnId : turnId,
                requirementFrameId != null ? requirementFrameId : frameId,
                requirementVersion != null ? requirementVersion : version);
    }
}
