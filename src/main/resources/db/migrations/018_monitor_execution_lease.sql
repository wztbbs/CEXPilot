-- 存量库已完成014～017后执行一次。新库schema.sql已包含最终结构，不执行本迁移。
-- 先停止所有旧版本应用实例并等待在途操作结束，再迁移并启动新版本；不可混跑。
-- 将原来的尝试期限收敛为唯一Execution租约期限，保持检查点、状态与token不变。
UPDATE monitor_execution
SET lease_until = CASE
    WHEN lease_until IS NULL THEN LEAST(attempt_deadline, deadline_at)
    WHEN attempt_deadline IS NULL THEN LEAST(lease_until, deadline_at)
    ELSE LEAST(lease_until, attempt_deadline, deadline_at)
END
WHERE status = 'RUNNING';

ALTER TABLE monitor_rule DROP COLUMN lease_until;
ALTER TABLE monitor_rule DROP COLUMN lease_token;
ALTER TABLE monitor_execution
    DROP COLUMN attempt_deadline;
