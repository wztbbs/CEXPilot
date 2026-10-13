-- 010: 将“下一个可用序号”改为“最后已分配的序号”。
-- 仅已存在 next_query_no 的老库执行；新库由 schema.sql 直接创建 last_query_no，无需执行。
-- 007 保留原定义，按历史迁移升级的老库需继续执行本文件。
-- 部署顺序：停止旧应用并等待在途问答结束 → 执行本文件 → 启动新应用。
-- 两版代码使用不同列，迁移期间不能继续接收问答。
--
-- 分三步保留旧值作为转换依据，避免对已转换的计数再次减 1。
-- MySQL DDL 会隐式提交；如中途失败，检查列是否存在，从未完成步骤继续，不要忽略错误继续执行。
-- 完成后不要重复执行。已有 conversation_query.query_no、状态版本与 fencing_token 均不改动。

-- 1) 新字段默认 0，表示还未分配轮次。
ALTER TABLE conversation_state
    ADD COLUMN last_query_no BIGINT NOT NULL DEFAULT 0 AFTER next_query_no;

-- 2) next=1 → last=0；next=4 → last=3，下次取得占用时分配第 4 轮。
--    在旧列仍存在且应用停止期间，这一步可安全重做。
UPDATE conversation_state
SET last_query_no = next_query_no - 1,
    updated_at = updated_at;

-- 3) 确认转换成功后移除旧列。
ALTER TABLE conversation_state
    DROP COLUMN next_query_no;
