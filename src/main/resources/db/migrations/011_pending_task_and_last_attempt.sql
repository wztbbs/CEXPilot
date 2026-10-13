-- 011: 审查修复 R1/R2——澄清任务与失败尝试独立于活动 frame 保存。
-- 背景：pendingClarification 原先 JSON_SET 进 active_frame_json 且要求该列非空，
-- 新会话首轮澄清直接丢失（R1）；FAILED 轮次不写 lastAttempt，"再试一次"接不上失败任务（R2）。
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出，无需执行本文件。
ALTER TABLE conversation_state
    ADD COLUMN pending_task_json JSON NULL AFTER active_frame_json,
    ADD COLUMN last_attempt_json JSON NULL AFTER pending_task_json;
-- 存量迁移说明：老 frame 里可能残留 pendingClarification 字段（旧机制写入），
-- 读取侧已兼容渲染，无需回填；新机制写入 pending_task_json 后以它为准。
