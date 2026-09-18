-- 002: ask_trace 增加 visitor_id 列（访客标识，cexpilot_uid cookie）
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出该列，无需执行本文件。
ALTER TABLE ask_trace ADD COLUMN visitor_id VARCHAR(64) NULL AFTER intent;
