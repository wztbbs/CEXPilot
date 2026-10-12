package com.cexpilot.evidence;

/**
 * 本轮问答的事实保存会话：事实归属于哪个会话、由哪一轮产生。
 * null（未携带）时跳过事实保存与绑定，保持单轮旧行为。
 */
public record EvidenceSession(String conversationId, String turnId) {
}
