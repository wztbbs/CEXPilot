-- 003: conversation 增加 visitor_id 列（1 访客 1 会话的关联键）
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出该列与索引，无需执行本文件。
ALTER TABLE conversation ADD COLUMN visitor_id VARCHAR(64) NULL AFTER conversation_id;
ALTER TABLE conversation ADD INDEX idx_conversation_visitor (visitor_id, last_active_at);
