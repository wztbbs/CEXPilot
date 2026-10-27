-- 主动监控第一阶段。存量库部署前执行；新库由 schema.sql 建表。
-- 所有 DATETIME 均为 UTC。长期业务记录与 ask_trace / conversation_artifact 独立。
CREATE TABLE IF NOT EXISTS monitor_rule (
    rule_id VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_version INT NULL,
    next_check_at DATETIME(6) NULL,
    last_evaluated_slot DATETIME(6) NULL,
    consecutive_hits INT NOT NULL DEFAULT 0,
    trigger_state VARCHAR(24) NOT NULL DEFAULT 'ARMED',
    hit_evidence_json JSON NULL,
    lease_until DATETIME(6) NULL,
    lease_token BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_monitor_request (owner_id, request_id),
    KEY idx_monitor_due (status, next_check_at),
    KEY idx_monitor_owner (owner_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS monitor_rule_version (
    rule_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    user_query TEXT NULL,
    rule_spec_json JSON NOT NULL,
    spec_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    approved_at DATETIME(6) NULL,
    PRIMARY KEY (rule_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS monitor_evaluation (
    evaluation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    rule_version INT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    data_cutoff_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NOT NULL,
    result VARCHAR(16) NOT NULL,
    evidence_json JSON NOT NULL,
    error_code VARCHAR(64) NULL,
    UNIQUE KEY uk_monitor_check (rule_id, rule_version, scheduled_at),
    KEY idx_monitor_eval_history (rule_id, scheduled_at),
    KEY idx_monitor_eval_cleanup (finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS monitor_event (
    event_id VARCHAR(64) NOT NULL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    rule_version INT NOT NULL,
    evaluation_id VARCHAR(64) NOT NULL,
    event_key CHAR(64) NOT NULL,
    triggered_at DATETIME(6) NOT NULL,
    evidence_snapshot JSON NOT NULL,
    UNIQUE KEY uk_monitor_event (event_key),
    KEY idx_monitor_event_history (rule_id, triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 同时作为 transactional outbox；Resend 投递复用 delivery_id 幂等键，UNKNOWN 不自动重发。
CREATE TABLE IF NOT EXISTS notification_delivery (
    delivery_id VARCHAR(64) NOT NULL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    lease_token BIGINT NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(255) NULL,
    last_error TEXT NULL,
    sent_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_monitor_delivery (event_id, channel, recipient),
    KEY idx_monitor_delivery_due (status, next_retry_at),
    KEY idx_monitor_delivery_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
