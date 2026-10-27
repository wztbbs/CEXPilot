-- 聊天候选确认及灰度邮箱绑定。存量库执行一次；先完成 014/015。
CREATE TABLE IF NOT EXISTS monitor_subscriber (
    visitor_id VARCHAR(64) NOT NULL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE monitor_rule
    ADD COLUMN pending_version INT NOT NULL DEFAULT 1,
    ADD COLUMN source_conversation_id VARCHAR(64) NULL,
    ADD COLUMN recipient VARCHAR(255) NULL,
    ADD KEY idx_monitor_draft (owner_id, source_conversation_id, status);
ALTER TABLE monitor_rule_version
    ADD COLUMN source_turn_id VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_monitor_source_turn (source_turn_id);
