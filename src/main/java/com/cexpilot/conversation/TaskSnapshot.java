package com.cexpilot.conversation;

/**
 * 可回访任务（审查 R3 一期）：一轮成功问答的 frame 快照 + 轮次元数据。
 * turnId 同时充当 Planner 任务索引里的引用别名（task_ref），由服务端校验归属，
 * 模型不接触数据库主键以外的任何 id。
 *
 * @param queryNo  轮次序号（时间正序展示用）
 * @param turnId   轮次 id，即 task_ref 的合法取值
 * @param question 该轮用户问题原文（任务主题摘要）
 * @param frame    该轮提交时的完整有效条件快照（RESUME 时的继承对账基准）
 */
public record TaskSnapshot(long queryNo, String turnId, String question, QueryFrame frame) {
}
