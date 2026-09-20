-- 004: ask_trace 增加 created_at 单列索引（/api/traces 按时间窗口查询用）
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出该索引，无需执行本文件。
ALTER TABLE ask_trace ADD INDEX idx_trace_created_at (created_at);
