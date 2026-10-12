-- 007: 多轮追问阶段一 —— conversation_query 轮次化（turn 生命周期 + 唯一约束）与会话状态表 conversation_state
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出，无需执行本文件。
-- 前置检查：uk_query_conv_no 创建前必须先确认无重复序号（旧代码 MAX+1 并发下可能产生重复），
-- 有重复行需先人工清理/重排，代码不替 DBA 做决定：
--   SELECT conversation_id, query_no, COUNT(*) c FROM conversation_query
--   GROUP BY conversation_id, query_no HAVING c > 1;

-- 1) conversation_query 轮次列（含阶段一 b 的 logical_plan_json）。
--    存量行这些列全为 NULL，读取方按 NULL=历史成功轮次处理。
ALTER TABLE conversation_query
    ADD COLUMN turn_id           VARCHAR(64) NULL AFTER trace_id,
    ADD COLUMN parent_turn_id    VARCHAR(64) NULL AFTER turn_id,
    ADD COLUMN turn_status       VARCHAR(32) NULL AFTER parent_turn_id,
    ADD COLUMN started_at        DATETIME(6) NULL AFTER turn_status,
    ADD COLUMN finished_at       DATETIME(6) NULL AFTER started_at,
    ADD COLUMN logical_plan_json JSON        NULL AFTER finished_at;

-- 2) 唯一键：turn_id 旧数据全 NULL（MySQL 唯一键允许多个 NULL）可直接加；
--    (conversation_id, query_no) 必须先通过上面的前置检查再执行。
ALTER TABLE conversation_query
    ADD UNIQUE KEY uk_query_turn (turn_id),
    ADD UNIQUE KEY uk_query_conv_no (conversation_id, query_no);

-- 3) 会话状态表：原子序号分配 + 同会话串行执行占用（running_turn_id/lease_until/fencing_token），
--    active_frame_json 为阶段一 b 的活动 QueryFrame。存量会话无需回填，首次提问时自动建行。
CREATE TABLE IF NOT EXISTS conversation_state (
    conversation_id   VARCHAR(64) NOT NULL PRIMARY KEY,
    version           BIGINT      NOT NULL DEFAULT 0,
    next_query_no     BIGINT      NOT NULL DEFAULT 1,
    running_turn_id   VARCHAR(64) NULL,
    lease_until       DATETIME(6) NULL,
    fencing_token     BIGINT      NOT NULL DEFAULT 0,
    active_frame_json JSON        NULL,
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
