package com.cexpilot.conversation;

/** 客户端看到的上一轮与服务端已完成轮次不同；拒绝执行，不能静默切换继承对象。 */
public final class StaleContextException extends RuntimeException {
    private final String conversationId;
    private final String currentParentTurnId;

    public StaleContextException(String conversationId, String currentParentTurnId) {
        super("会话已在其他页面更新，请刷新历史并确认上下文后重新提问。");
        this.conversationId = conversationId;
        this.currentParentTurnId = currentParentTurnId;
    }

    public java.util.Map<String, Object> response() {
        return java.util.Map.of("code", "CONTEXT_CONFLICT", "error", getMessage(),
                "conversationId", conversationId,
                "currentParentTurnId", currentParentTurnId == null ? "" : currentParentTurnId);
    }
}
