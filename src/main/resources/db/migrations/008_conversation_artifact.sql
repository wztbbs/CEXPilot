-- 008: 多轮追问阶段二 —— 历史结果引用（FACT artifact + REUSE_FACT）
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出，无需执行本文件。
-- 上线顺序：先执行本迁移，后部署代码（代码会写 VARCHAR(16) 放不下的新事件名）。

-- 1) trace_event.event_type 加宽：新事件 EVIDENCE_REUSE_DECISION（22 字符）超出旧 VARCHAR(16)。
ALTER TABLE trace_event MODIFY COLUMN event_type VARCHAR(32) NOT NULL;

-- 2) ask_trace 汇总列：本轮复用的历史事实数（FACT_REUSED 事件的独立计数）。
ALTER TABLE ask_trace ADD COLUMN reused_facts INT NOT NULL DEFAULT 0 AFTER tool_calls;

-- 3) 会话级取数事实（artifact）。本期 kind 固定 'FACT'，列保留给阶段四的 DATASET。
--    payload_json 为完整 MetricResultJson 输出（identity + data + metadata）；
--    单条 payload 超 256KB 不缓存：payload_json 为 NULL，metadata_json.cache_skip_reason 记录原因。
--    retained_until = 保存时 +24h，过期由 deleteExpired 清理；清理后引用走 UNAVAILABLE 路径。
CREATE TABLE IF NOT EXISTS conversation_artifact (
    artifact_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL DEFAULT 'FACT',
    source_turn_id  VARCHAR(64)  NULL,
    source_trace_id VARCHAR(64)  NULL,
    lookup_key      CHAR(64)     NOT NULL,
    schema_version  VARCHAR(16)  NOT NULL,
    derived         TINYINT(1)   NOT NULL DEFAULT 0, -- 算子派生结果；不可作为样本集来源
    metadata_json   JSON         NOT NULL,           -- identity / observed_at / quality / source_fact_ids / cache_skip_reason
    payload_json    JSON         NULL,
    fetched_at      DATETIME(6)  NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retained_until  DATETIME(6)  NOT NULL,
    KEY idx_artifact_conv (conversation_id, kind, lookup_key),
    KEY idx_artifact_retained (retained_until)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
